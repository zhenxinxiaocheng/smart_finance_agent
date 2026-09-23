package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartfinance.agent.investment.dto.*;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import com.smartfinance.agent.investment.quant.BenchmarkProfile;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class InvestmentAssetServiceImpl implements InvestmentAssetService {

    private static final String DEFAULT_ACCOUNT_NAME = "默认投资账户";
    private static final Set<String> HOLDING_SNAPSHOT_SOURCES = Set.of(
            "ASSET_CRUD", "RECURRING_PLAN");

    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final InvestmentAccountMapper accountMapper;
    private final InvestmentPositionMapper positionMapper;
    private final InvestmentTransactionMapper transactionMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentService investmentService;
    private final InvestmentDataJobService dataJobService;
    private final InvestmentRuntimeProperties runtimeProperties;
    private final ChinaTradingCalendarService tradingCalendar;
    private final FundClassificationService classificationService;
    private final QuantBenchmarkProfileService benchmarkProfileService;
    private final InvestmentDetailCacheService detailCache;
    private final InvestmentQuoteCacheService quoteCache;
    private final ConcurrentHashMap<ProductKey, CompletableFuture<RefreshOutcome>> productRefreshes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProductKey, CachedRefreshOutcome> refreshOutcomes = new ConcurrentHashMap<>();
    private final ExecutorService refreshExecutor;

    public InvestmentAssetServiceImpl(InvestmentAssetMapper assetMapper,
                                      InvestmentProductMapper productMapper,
                                      InvestmentAccountMapper accountMapper,
                                      InvestmentPositionMapper positionMapper,
                                      InvestmentTransactionMapper transactionMapper,
                                      ProductDailyQuoteMapper quoteMapper,
                                      AnalysisServiceClient analysisClient,
                                      InvestmentService investmentService,
                                      InvestmentDataJobService dataJobService,
                                      InvestmentRuntimeProperties runtimeProperties,
                                      ChinaTradingCalendarService tradingCalendar,
                                      FundClassificationService classificationService,
                                      QuantBenchmarkProfileService benchmarkProfileService,
                                      InvestmentDetailCacheService detailCache,
                                      InvestmentQuoteCacheService quoteCache) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.transactionMapper = transactionMapper;
        this.quoteMapper = quoteMapper;
        this.analysisClient = analysisClient;
        this.investmentService = investmentService;
        this.dataJobService = dataJobService;
        this.runtimeProperties = runtimeProperties;
        this.tradingCalendar = tradingCalendar;
        this.classificationService = classificationService;
        this.benchmarkProfileService = benchmarkProfileService;
        this.detailCache = detailCache;
        this.quoteCache = quoteCache;
        this.refreshExecutor = createRefreshExecutor(runtimeProperties.getMarket().getActiveRefreshConcurrency());
    }

    @Override
    public AnalysisServiceClient.ResolvedProduct resolve(Long userId, String productType, String code) {
        return analysisClient.resolveProduct(normalizeType(productType), normalizeCode(code));
    }

    @Override
    @Transactional
    public InvestmentAssetView create(Long userId, InvestmentAssetCreateRequest request) {
        AnalysisServiceClient.ResolvedProduct resolved = resolve(userId, request.getProductType(), request.getCode());
        InvestmentProduct product = upsertProduct(resolved);
        Long duplicate = assetMapper.selectCount(new LambdaQueryWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getUserId, userId)
                .eq(InvestmentAsset::getProductId, product.getId()));
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("该股票或基金已经添加");

        InvestmentAsset asset = new InvestmentAsset();
        asset.setUserId(userId);
        asset.setAccountId(defaultAccount(userId).getId());
        asset.setProductId(product.getId());
        asset.setQuantity(request.getQuantity());
        asset.setAverageCost(request.getAverageCost());
        asset.setNote(blankToNull(request.getNote()));
        asset.setSyncStatus(resolved.latestPrice() == null ? "PARTIAL" : "SUCCESS");
        asset.setSyncError(resolved.warnings().isEmpty() ? null : String.join("；", resolved.warnings()));
        asset.setDeleted(0);
        assetMapper.insert(asset);
        saveResolvedQuote(product, resolved);
        refreshAsset(asset, true, resolved);
        if (request.getQuantity() != null || request.getAverageCost() != null) {
            replaceHolding(userId, asset, request.getQuantity(), request.getAverageCost(), asset.getNote());
        }
        dataJobService.ensureQueued(userId, asset.getId(), product.getId(), product.getProductType(), false);
        if (resolved.benchmarkCode() != null) {
            dataJobService.ensureBenchmarkQueued(userId, asset.getId(), product.getId());
        }
        return toView(asset);
    }

    @Override
    public List<InvestmentAssetView> list(Long userId) {
        return assetMapper.selectList(new LambdaQueryWrapper<InvestmentAsset>()
                        .eq(InvestmentAsset::getUserId, userId)
                        .orderByDesc(InvestmentAsset::getUpdatedAt))
                .stream().map(this::toView).toList();
    }

    @Override
    public InvestmentAssetView get(Long userId, Long assetId) {
        return toView(requireAsset(userId, assetId));
    }

    @Override
    @Transactional
    public InvestmentAssetView update(Long userId, Long assetId, InvestmentAssetUpdateRequest request) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        replaceHolding(userId, asset, request.getQuantity(), request.getAverageCost(), blankToNull(request.getNote()));
        return toView(asset);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long assetId) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        reverseCurrent(userId, asset);
        assetMapper.deleteById(asset.getId());
        detailCache.evict(userId, assetId);
        invalidateUserDetails(userId);
    }

    @Override
    @Transactional
    public InvestmentAssetView sync(Long userId, Long assetId) {
        return refresh(userId, assetId, true);
    }

    @Override
    public InvestmentAssetView refresh(Long userId, Long assetId, boolean force) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        refreshAsset(asset, force);
        queueBenchmarkHistory(asset);
        return toView(asset);
    }

    @Override
    public List<InvestmentAssetView> refreshAll(Long userId, boolean force) {
        List<InvestmentAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getUserId, userId)
                .orderByDesc(InvestmentAsset::getUpdatedAt));
        List<CompletableFuture<Void>> refreshTasks = assets.stream()
                .map(asset -> CompletableFuture.runAsync(() -> {
                    refreshAsset(asset, force);
                    queueBenchmarkHistory(asset);
                }, refreshExecutor))
                .toList();
        CompletableFuture.allOf(refreshTasks.toArray(CompletableFuture[]::new)).join();
        return assets.stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public InvestmentAssetView applyRecurringInvestment(Long userId, Long accountId, Long productId,
                                                        BigDecimal amount, BigDecimal price,
                                                        Long planId, LocalDate tradeDate) {
        if (amount == null || amount.signum() <= 0 || price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("定投金额和成交净值必须大于 0");
        }
        InvestmentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getUserId, userId)
                .eq(InvestmentAsset::getAccountId, accountId)
                .eq(InvestmentAsset::getProductId, productId)
                .last("LIMIT 1"));
        if (asset == null) throw new IllegalArgumentException("定投对应的持仓不存在");

        BigDecimal oldQuantity = asset.getQuantity() == null ? BigDecimal.ZERO : asset.getQuantity();
        BigDecimal oldAverageCost = asset.getAverageCost() == null ? BigDecimal.ZERO : asset.getAverageCost();
        BigDecimal purchasedQuantity = amount.divide(price, 10, RoundingMode.HALF_UP);
        BigDecimal newQuantity = oldQuantity.add(purchasedQuantity);
        BigDecimal newCostAmount = oldQuantity.multiply(oldAverageCost).add(amount);
        BigDecimal newAverageCost = newCostAmount.divide(newQuantity, 10, RoundingMode.HALF_UP);

        replaceHolding(userId, asset, newQuantity, newAverageCost, asset.getNote(),
                "RECURRING_PLAN", "plan-" + planId + "-" + tradeDate, tradeDate);
        return toView(asset);
    }

    private void refreshAsset(InvestmentAsset asset, boolean force) {
        refreshAsset(asset, force, null);
    }

    private void refreshAsset(InvestmentAsset asset,
                              boolean force,
                              AnalysisServiceClient.ResolvedProduct alreadyResolved) {
        InvestmentProduct currentProduct = productMapper.selectById(asset.getProductId());
        if (currentProduct == null) {
            applyOutcome(asset, new RefreshOutcome("FAILED", "投资产品不存在"));
            return;
        }
        ProductKey productKey = ProductKey.from(currentProduct);
        LocalDateTime now = LocalDateTime.now(runtimeProperties.getMarket().getZone());
        CachedRefreshOutcome cached = refreshOutcomes.get(productKey);
        if (!force && canReuse(currentProduct, cached, now)) {
            applyOutcome(asset, cached.outcome());
            return;
        }

        CompletableFuture<RefreshOutcome> ownRefresh = new CompletableFuture<>();
        CompletableFuture<RefreshOutcome> existingRefresh = productRefreshes.putIfAbsent(
                productKey, ownRefresh);
        if (existingRefresh != null) {
            applyOutcome(asset, existingRefresh.join());
            return;
        }

        try {
            RefreshOutcome outcome = fetchLatest(currentProduct, alreadyResolved, force);
            LocalDateTime completedAt = LocalDateTime.now(runtimeProperties.getMarket().getZone());
            refreshOutcomes.put(productKey, new CachedRefreshOutcome(outcome, completedAt));
            applyOutcome(asset, outcome);
            ownRefresh.complete(outcome);
        } finally {
            if (!ownRefresh.isDone()) {
                RefreshOutcome failed = new RefreshOutcome("FAILED", "行情刷新过程异常");
                refreshOutcomes.put(productKey, new CachedRefreshOutcome(failed,
                        LocalDateTime.now(runtimeProperties.getMarket().getZone())));
                ownRefresh.complete(failed);
            }
            productRefreshes.remove(productKey, ownRefresh);
        }
    }

    private RefreshOutcome fetchLatest(InvestmentProduct product,
                                       AnalysisServiceClient.ResolvedProduct alreadyResolved,
                                       boolean force) {
        try {
            if ("STOCK".equals(product.getProductType())) {
                InvestmentQuoteCacheService.Entry cached = force ? null
                        : quoteCache.get(product.getMarket(), product.getCode());
                if (cached != null) {
                    saveRealtimeQuote(product, cached.quote());
                    return new RefreshOutcome("SUCCESS", joinWarnings(cached.quote().warnings()),
                            LocalDateTime.ofInstant(cached.expiresAt(), runtimeProperties.getMarket().getZone()));
                }
                AnalysisServiceClient.RealtimeQuote quote = analysisClient.realtimeQuote(
                        product.getCode(), product.getMarket());
                Instant retrievedAt = Instant.now();
                saveRealtimeQuote(product, quote);
                quoteCache.put(product.getMarket(), product.getCode(), quote, retrievedAt);
                return new RefreshOutcome("SUCCESS", joinWarnings(quote.warnings()));
            }
            AnalysisServiceClient.ResolvedProduct resolved = alreadyResolved != null
                    ? alreadyResolved
                    : analysisClient.resolveProduct(product.getProductType(), product.getCode());
            InvestmentProduct resolvedProduct = upsertProduct(resolved);
            saveResolvedQuote(resolvedProduct, resolved);
            return new RefreshOutcome(resolved.latestPrice() == null ? "PARTIAL" : "SUCCESS",
                    joinWarnings(resolved.warnings()));
        } catch (RuntimeException exception) {
            return new RefreshOutcome("FAILED", exception.getMessage());
        }
    }

    private void applyOutcome(InvestmentAsset asset, RefreshOutcome outcome) {
        asset.setSyncStatus(outcome.status());
        asset.setSyncError(outcome.error());
        assetMapper.update(null, new LambdaUpdateWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getId, asset.getId())
                .set(InvestmentAsset::getSyncStatus, outcome.status())
                .set(InvestmentAsset::getSyncError, outcome.error()));
    }

    private void queueBenchmarkHistory(InvestmentAsset asset) {
        InvestmentProduct product = productMapper.selectById(asset.getProductId());
        if (product == null || !"MUTUAL_FUND".equals(product.getProductType())) {
            return;
        }
        BenchmarkProfile profile = benchmarkProfileService.configuration(
                product.getProductType(), product.getCode(), LocalDate.now()
        );
        if (profile != null && Objects.equals(product.getCode(), profile.getProductCode())) {
            dataJobService.ensureBenchmarkQueued(
                    asset.getUserId(), asset.getId(), product.getId()
            );
        }
    }

    private boolean canReuse(InvestmentProduct product, CachedRefreshOutcome cached, LocalDateTime now) {
        if (cached == null || "FAILED".equals(cached.outcome().status())) return false;
        if ("STOCK".equals(product.getProductType()) && !isStockMarketOpen(now)) return true;
        if (cached.outcome().validUntil() != null && !now.isBefore(cached.outcome().validUntil())) return false;
        long freshnessMs = "STOCK".equals(product.getProductType())
                ? runtimeProperties.getMarket().getStockActiveFreshnessMs()
                : runtimeProperties.getMarket().getFundActiveFreshnessMs();
        return Duration.between(cached.refreshedAt(), now).toMillis() < freshnessMs;
    }

    private boolean isStockMarketOpen(LocalDateTime now) {
        LocalTime time = now.toLocalTime();
        return tradingCalendar.isTradingDay(now.toLocalDate())
                && !time.isBefore(runtimeProperties.getMarket().getStockRefreshStart())
                && !time.isAfter(runtimeProperties.getMarket().getStockRefreshEnd());
    }

    private static String joinWarnings(List<String> warnings) {
        return warnings == null || warnings.isEmpty() ? null : String.join("；", warnings);
    }

    @PreDestroy
    void shutdownRefreshExecutor() {
        refreshExecutor.shutdownNow();
    }

    private static ExecutorService createRefreshExecutor(int configuredConcurrency) {
        int concurrency = Math.max(1, configuredConcurrency);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "investment-active-refresh-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency * 4), threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private record ProductKey(Long id, LocalDateTime createdAt) {
        private static ProductKey from(InvestmentProduct product) {
            return new ProductKey(product.getId(), product.getCreatedAt());
        }
    }

    private record CachedRefreshOutcome(RefreshOutcome outcome, LocalDateTime refreshedAt) {
    }

    private record RefreshOutcome(String status, String error, LocalDateTime validUntil) {
        private RefreshOutcome(String status, String error) { this(status, error, null); }
    }

    private void replaceHolding(Long userId, InvestmentAsset asset, BigDecimal quantity,
                                BigDecimal averageCost, String note) {
        replaceHolding(userId, asset, quantity, averageCost, note, "ASSET_CRUD",
                "asset-" + asset.getId() + "-" + System.nanoTime(), LocalDate.now());
    }

    private void replaceHolding(Long userId, InvestmentAsset asset, BigDecimal quantity,
                                BigDecimal averageCost, String note, String source,
                                String externalRef, LocalDate tradeDate) {
        if ((quantity == null) != (averageCost == null)) {
            throw new IllegalArgumentException("份额和持仓成本价必须同时填写或同时清空");
        }
        reverseCurrent(userId, asset);
        asset.setQuantity(quantity);
        asset.setAverageCost(averageCost);
        asset.setNote(note);
        asset.setCurrentTransactionId(null);
        if (quantity != null) {
            InvestmentProduct product = productMapper.selectById(asset.getProductId());
            InvestmentTransactionRequest request = new InvestmentTransactionRequest();
            request.setAccountId(asset.getAccountId());
            request.setEventType("TRANSFER_IN");
            request.setTradeDate(tradeDate);
            request.setCurrency(product.getCurrency());
            request.setQuantity(quantity);
            request.setPrice(averageCost);
            request.setFee(BigDecimal.ZERO);
            request.setSource(source);
            request.setExternalRef(externalRef);
            request.setNote(note);
            request.setProduct(productRequest(product));
            InvestmentTransaction transaction = investmentService.addTransaction(userId, request);
            asset.setCurrentTransactionId(transaction.getId());
        }
        asset.setUpdatedAt(LocalDateTime.now());
        assetMapper.updateById(asset);
        invalidateUserDetails(userId);
    }

    private void invalidateUserDetails(Long userId) {
        // Holdings change the user's total wealth and therefore other assets' risk warnings too.
        for (InvestmentAsset asset : assetMapper.selectList(new LambdaQueryWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getUserId, userId))) {
            detailCache.evict(userId, asset.getId());
        }
    }

    private void reverseCurrent(Long userId, InvestmentAsset asset) {
        List<InvestmentTransaction> snapshots = transactionMapper.selectList(
                new LambdaQueryWrapper<InvestmentTransaction>()
                        .eq(InvestmentTransaction::getUserId, userId)
                        .eq(InvestmentTransaction::getAccountId, asset.getAccountId())
                        .eq(InvestmentTransaction::getProductId, asset.getProductId())
                        .in(InvestmentTransaction::getSource, HOLDING_SNAPSHOT_SOURCES)
                        .orderByDesc(InvestmentTransaction::getId));
        if (snapshots.isEmpty()) {
            return;
        }
        Set<Long> snapshotIds = snapshots.stream()
                .map(InvestmentTransaction::getId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> reversedIds = transactionMapper.selectList(
                        new LambdaQueryWrapper<InvestmentTransaction>()
                                .eq(InvestmentTransaction::getUserId, userId)
                                .eq(InvestmentTransaction::getEventType, "REVERSAL")
                                .in(InvestmentTransaction::getReversalTransactionId, snapshotIds))
                .stream()
                .map(InvestmentTransaction::getReversalTransactionId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (InvestmentTransaction snapshot : snapshots) {
            if (!reversedIds.contains(snapshot.getId())) {
                investmentService.reverseTransaction(userId, snapshot.getId(), "资产列表持仓更正");
            }
        }
    }

    private InvestmentAccount defaultAccount(Long userId) {
        InvestmentAccount existing = accountMapper.selectOne(new LambdaQueryWrapper<InvestmentAccount>()
                .eq(InvestmentAccount::getUserId, userId)
                .eq(InvestmentAccount::getAccountName, DEFAULT_ACCOUNT_NAME)
                .last("LIMIT 1"));
        if (existing != null) return existing;
        InvestmentAccountRequest request = new InvestmentAccountRequest();
        request.setAccountName(DEFAULT_ACCOUNT_NAME);
        request.setAccountType("VIRTUAL");
        request.setBaseCurrency("CNY");
        return investmentService.createAccount(userId, request);
    }

    private InvestmentProduct upsertProduct(AnalysisServiceClient.ResolvedProduct resolved) {
        InvestmentProduct product = productMapper.selectOne(new LambdaQueryWrapper<InvestmentProduct>()
                .eq(InvestmentProduct::getProductType, resolved.productType())
                .eq(InvestmentProduct::getMarket, resolved.market())
                .eq(InvestmentProduct::getCode, resolved.code()));
        if (product == null) {
            product = new InvestmentProduct();
            product.setProductType(resolved.productType());
            product.setMarket(resolved.market());
            product.setCode(resolved.code());
            product.setStatus("ACTIVE");
        }
        product.setName(resolved.name());
        product.setCurrency(resolved.currency());
        if (resolved.inceptionDate() != null) {
            product.setInceptionDate(resolved.inceptionDate());
        }
        if (product.getHistoryCoverageComplete() == null) {
            product.setHistoryCoverageComplete(false);
        }
        classificationService.applyResolved(product, resolved);
        if (product.getId() == null) productMapper.insert(product); else productMapper.updateById(product);
        benchmarkProfileService.configureImportedFundBenchmark(product, resolved);
        return product;
    }

    private void saveResolvedQuote(InvestmentProduct product, AnalysisServiceClient.ResolvedProduct resolved) {
        if (resolved.latestPrice() == null || resolved.dataDate() == null) return;
        ProductDailyQuote quote = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .eq(ProductDailyQuote::getTradeDate, resolved.dataDate())
                .eq(ProductDailyQuote::getAdjustType, runtimeProperties.getDataQuality().getRealtimeAdjustType()));
        if (quote != null && "MUTUAL_FUND".equals(product.getProductType())
                && quote.getTotalReturnIndex() != null
                && quote.getTotalReturnIndex().signum() > 0) {
            return;
        }
        if (quote == null) {
            quote = new ProductDailyQuote();
            quote.setProductId(product.getId());
            quote.setTradeDate(resolved.dataDate());
            quote.setAdjustType(runtimeProperties.getDataQuality().getRealtimeAdjustType());
        }
        quote.setClosePrice(resolved.latestPrice());
        quote.setPreviousClose(resolved.previousClose());
        quote.setChangeAmount(resolved.changeAmount());
        quote.setChangePercent(resolved.changePercent());
        quote.setOpenPrice(resolved.openPrice());
        quote.setHighPrice(resolved.highPrice());
        quote.setLowPrice(resolved.lowPrice());
        quote.setVolume(resolved.volume());
        quote.setAmount(resolved.amount());
        quote.setTurnoverRate(resolved.turnoverRate());
        quote.setVolumeRatio(resolved.volumeRatio());
        quote.setAmplitude(resolved.amplitude());
        quote.setSource(resolved.provider());
        quote.setAdapterVersion("resolve-v1");
        quote.setSyncedAt(LocalDateTime.now());
        if (quote.getId() == null) quoteMapper.insert(quote); else quoteMapper.updateById(quote);
    }

    private void saveRealtimeQuote(InvestmentProduct product, AnalysisServiceClient.RealtimeQuote resolved) {
        ProductDailyQuote quote = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .eq(ProductDailyQuote::getTradeDate, resolved.dataDate())
                .eq(ProductDailyQuote::getAdjustType, runtimeProperties.getDataQuality().getRealtimeAdjustType()));
        if (quote == null) {
            quote = new ProductDailyQuote();
            quote.setProductId(product.getId());
            quote.setTradeDate(resolved.dataDate());
            quote.setAdjustType(runtimeProperties.getDataQuality().getRealtimeAdjustType());
        }
        quote.setClosePrice(resolved.latestPrice());
        quote.setPreviousClose(resolved.previousClose());
        quote.setChangeAmount(resolved.changeAmount());
        quote.setChangePercent(resolved.changePercent());
        quote.setOpenPrice(resolved.openPrice());
        quote.setHighPrice(resolved.highPrice());
        quote.setLowPrice(resolved.lowPrice());
        quote.setVolume(resolved.volume());
        quote.setAmount(resolved.amount());
        quote.setTurnoverRate(resolved.turnoverRate());
        quote.setVolumeRatio(resolved.volumeRatio());
        quote.setAmplitude(resolved.amplitude());
        quote.setSource(resolved.provider());
        quote.setAdapterVersion("realtime-v1");
        quote.setSyncedAt(resolved.fetchedAt());
        if (quote.getId() == null) quoteMapper.insert(quote); else quoteMapper.updateById(quote);
    }

    private InvestmentAsset requireAsset(Long userId, Long assetId) {
        InvestmentAsset asset = assetMapper.selectById(assetId);
        if (asset == null || !Objects.equals(asset.getUserId(), userId)) {
            throw new IllegalArgumentException("资产不存在");
        }
        return asset;
    }

    private InvestmentAssetView toView(InvestmentAsset asset) {
        InvestmentProduct product = productMapper.selectById(asset.getProductId());
        InvestmentPosition position = positionMapper.selectOne(new LambdaQueryWrapper<InvestmentPosition>()
                .eq(InvestmentPosition::getAccountId, asset.getAccountId())
                .eq(InvestmentPosition::getProductId, asset.getProductId()));
        ProductDailyQuote quote = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, asset.getProductId())
                .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
        InvestmentAssetView view = new InvestmentAssetView();
        view.setId(asset.getId());
        view.setAccountId(asset.getAccountId());
        view.setProductId(asset.getProductId());
        view.setProductType(product.getProductType());
        view.setFundTypeRaw(product.getFundTypeRaw());
        view.setFundCategory(product.getFundCategory());
        view.setClassificationSource(product.getClassificationSource());
        view.setClassificationVersion(product.getClassificationVersion());
        view.setCode(product.getCode());
        view.setName(product.getName());
        view.setMarket(product.getMarket());
        view.setCurrency(product.getCurrency());
        view.setQuantity(asset.getQuantity());
        view.setAverageCost(asset.getAverageCost());
        BigDecimal latestPrice = quote != null ? quote.getClosePrice()
                : position == null ? null : position.getLatestPrice();
        view.setLatestPrice(latestPrice);
        if (quote != null) {
            BigDecimal previousClose = quote.getPreviousClose();
            if (previousClose == null) {
                ProductDailyQuote previousQuote = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                        .eq(ProductDailyQuote::getProductId, quote.getProductId())
                        .eq(ProductDailyQuote::getAdjustType, quote.getAdjustType())
                        .lt(ProductDailyQuote::getTradeDate, quote.getTradeDate())
                        .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
                previousClose = previousQuote == null ? null : previousQuote.getClosePrice();
            }
            BigDecimal changeAmount = quote.getChangeAmount();
            if (changeAmount == null && latestPrice != null && previousClose != null) {
                changeAmount = latestPrice.subtract(previousClose);
            }
            BigDecimal changePercent = quote.getChangePercent();
            if (changePercent == null && changeAmount != null && previousClose != null
                    && previousClose.signum() != 0) {
                changePercent = changeAmount.divide(previousClose, 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
            view.setPreviousClose(previousClose);
            view.setChangeAmount(changeAmount);
            view.setChangePercent(changePercent);
            view.setOpenPrice(quote.getOpenPrice());
            view.setHighPrice(quote.getHighPrice());
            view.setLowPrice(quote.getLowPrice());
            view.setVolume(quote.getVolume());
            view.setAmount(quote.getAmount());
            view.setTurnoverRate(quote.getTurnoverRate());
            view.setVolumeRatio(quote.getVolumeRatio());
            view.setAmplitude(quote.getAmplitude());
        }
        if ("CNY".equals(product.getCurrency())) {
            view.setMarketValueCny(calculatedMarketValue(asset, latestPrice));
            view.setUnrealizedPnlCny(calculatedPnl(asset, latestPrice));
        } else {
            view.setMarketValueCny(position == null ? null : position.getMarketValueCny());
            view.setUnrealizedPnlCny(position == null ? null : position.getUnrealizedPnlCny());
        }
        view.setHoldingReturnPercent(calculatedReturnPercent(asset, latestPrice));
        view.setDataDate(quote != null ? quote.getTradeDate()
                : position == null ? null : position.getDataDate());
        view.setFetchedAt(quote == null ? null : quote.getSyncedAt());
        view.setNote(asset.getNote());
        view.setSyncStatus(asset.getSyncStatus());
        view.setSyncError(asset.getSyncError());
        view.setUpdatedAt(asset.getUpdatedAt());
        return view;
    }

    private static BigDecimal calculatedMarketValue(InvestmentAsset asset, BigDecimal price) {
        return asset.getQuantity() == null || price == null ? null : asset.getQuantity().multiply(price);
    }

    private static BigDecimal calculatedPnl(InvestmentAsset asset, BigDecimal price) {
        if (asset.getQuantity() == null || asset.getAverageCost() == null || price == null) return null;
        return price.subtract(asset.getAverageCost()).multiply(asset.getQuantity());
    }

    private static BigDecimal calculatedReturnPercent(InvestmentAsset asset, BigDecimal price) {
        if (asset.getAverageCost() == null || asset.getAverageCost().signum() == 0 || price == null) return null;
        return price.subtract(asset.getAverageCost())
                .divide(asset.getAverageCost(), 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private static InvestmentProductRequest productRequest(InvestmentProduct product) {
        InvestmentProductRequest request = new InvestmentProductRequest();
        request.setProductType(product.getProductType());
        request.setMarket(product.getMarket());
        request.setCode(product.getCode());
        request.setName(product.getName());
        request.setCurrency(product.getCurrency());
        return request;
    }

    private static String normalizeType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if ("FUND".equals(normalized)) normalized = "MUTUAL_FUND";
        if (!List.of("STOCK", "MUTUAL_FUND").contains(normalized)) {
            throw new IllegalArgumentException("只支持股票或基金");
        }
        return normalized;
    }

    private static String normalizeCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("\\d{6}")) throw new IllegalArgumentException("请输入 6 位股票或基金代码");
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
