package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.dto.*;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class InvestmentAssetServiceImpl implements InvestmentAssetService {

    private static final String DEFAULT_ACCOUNT_NAME = "默认投资账户";

    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final InvestmentAccountMapper accountMapper;
    private final InvestmentPositionMapper positionMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentService investmentService;
    private final InvestmentRuntimeProperties runtimeProperties;

    public InvestmentAssetServiceImpl(InvestmentAssetMapper assetMapper,
                                      InvestmentProductMapper productMapper,
                                      InvestmentAccountMapper accountMapper,
                                      InvestmentPositionMapper positionMapper,
                                      ProductDailyQuoteMapper quoteMapper,
                                      AnalysisServiceClient analysisClient,
                                      InvestmentService investmentService,
                                      InvestmentRuntimeProperties runtimeProperties) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.quoteMapper = quoteMapper;
        this.analysisClient = analysisClient;
        this.investmentService = investmentService;
        this.runtimeProperties = runtimeProperties;
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
        if (request.getQuantity() != null || request.getAverageCost() != null) {
            replaceHolding(userId, asset, request.getQuantity(), request.getAverageCost(), asset.getNote());
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
    }

    @Override
    @Transactional
    public InvestmentAssetView sync(Long userId, Long assetId) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        InvestmentProduct currentProduct = productMapper.selectById(asset.getProductId());
        try {
            if ("STOCK".equals(currentProduct.getProductType())) {
                AnalysisServiceClient.RealtimeQuote quote = analysisClient.realtimeQuote(
                        currentProduct.getCode(), currentProduct.getMarket());
                saveRealtimeQuote(currentProduct, quote);
                asset.setSyncStatus("SUCCESS");
                asset.setSyncError(quote.warnings().isEmpty() ? null : String.join("；", quote.warnings()));
            } else {
                AnalysisServiceClient.ResolvedProduct resolved = analysisClient.resolveProduct(
                        currentProduct.getProductType(), currentProduct.getCode());
                InvestmentProduct product = upsertProduct(resolved);
                saveResolvedQuote(product, resolved);
                asset.setSyncStatus(resolved.latestPrice() == null ? "PARTIAL" : "SUCCESS");
                asset.setSyncError(resolved.warnings().isEmpty() ? null : String.join("；", resolved.warnings()));
            }
        } catch (RuntimeException exception) {
            asset.setSyncStatus("FAILED");
            asset.setSyncError(exception.getMessage());
        }
        assetMapper.updateById(asset);
        return toView(asset);
    }

    private void replaceHolding(Long userId, InvestmentAsset asset, BigDecimal quantity,
                                BigDecimal averageCost, String note) {
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
            request.setTradeDate(LocalDate.now());
            request.setCurrency(product.getCurrency());
            request.setQuantity(quantity);
            request.setPrice(averageCost);
            request.setFee(BigDecimal.ZERO);
            request.setSource("ASSET_CRUD");
            request.setExternalRef("asset-" + asset.getId() + "-" + System.nanoTime());
            request.setNote(note);
            request.setProduct(productRequest(product));
            InvestmentTransaction transaction = investmentService.addTransaction(userId, request);
            asset.setCurrentTransactionId(transaction.getId());
        }
        asset.setUpdatedAt(LocalDateTime.now());
        assetMapper.updateById(asset);
    }

    private void reverseCurrent(Long userId, InvestmentAsset asset) {
        if (asset.getCurrentTransactionId() != null) {
            investmentService.reverseTransaction(userId, asset.getCurrentTransactionId(), "资产列表持仓更正");
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
        if (product.getId() == null) productMapper.insert(product); else productMapper.updateById(product);
        return product;
    }

    private void saveResolvedQuote(InvestmentProduct product, AnalysisServiceClient.ResolvedProduct resolved) {
        if (resolved.latestPrice() == null || resolved.dataDate() == null) return;
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
