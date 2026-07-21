package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.domain.InvestmentLedgerCalculator;
import com.smartfinance.agent.investment.domain.LedgerEvent;
import com.smartfinance.agent.investment.domain.LedgerState;
import com.smartfinance.agent.investment.dto.*;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InvestmentServiceImpl implements InvestmentService {

    private static final Set<String> EVENTS = Set.of(
            "DEPOSIT", "WITHDRAWAL", "BUY", "SELL", "DIVIDEND", "DIVIDEND_REINVEST",
            "FEE", "TRANSFER_IN", "TRANSFER_OUT", "SPLIT", "MERGE", "REVERSAL", "CORRECTION"
    );
    private static final Set<String> PRODUCT_EVENTS = Set.of(
            "BUY", "SELL", "DIVIDEND", "DIVIDEND_REINVEST", "TRANSFER_IN", "TRANSFER_OUT", "SPLIT", "MERGE"
    );

    private final InvestmentProductMapper productMapper;
    private final InvestmentAccountMapper accountMapper;
    private final InvestmentTransactionMapper transactionMapper;
    private final InvestmentPositionMapper positionMapper;
    private final InvestmentCashBalanceMapper cashBalanceMapper;
    private final InvestmentCashLedgerMapper cashLedgerMapper;
    private final InvestmentImportBatchMapper importBatchMapper;
    private final InvestmentSyncBatchMapper syncBatchMapper;
    private final InvestmentPlanMapper planMapper;
    private final DailyExchangeRateMapper exchangeRateMapper;
    private final ChinaTradingCalendarService tradingCalendar;
    private final InvestmentRuntimeProperties runtimeProperties;
    private final InvestmentDataQualityService dataQualityService;
    private final ObjectMapper objectMapper;
    private final InvestmentCsvParser csvParser = new InvestmentCsvParser();
    private final InvestmentLedgerCalculator calculator = new InvestmentLedgerCalculator();

    public InvestmentServiceImpl(InvestmentProductMapper productMapper,
                                 InvestmentAccountMapper accountMapper,
                                 InvestmentTransactionMapper transactionMapper,
                                 InvestmentPositionMapper positionMapper,
                                 InvestmentCashBalanceMapper cashBalanceMapper,
                                 InvestmentCashLedgerMapper cashLedgerMapper,
                                 InvestmentImportBatchMapper importBatchMapper,
                                 InvestmentSyncBatchMapper syncBatchMapper,
                                 InvestmentPlanMapper planMapper,
                                 DailyExchangeRateMapper exchangeRateMapper,
                                 ChinaTradingCalendarService tradingCalendar,
                                 InvestmentRuntimeProperties runtimeProperties,
                                 InvestmentDataQualityService dataQualityService,
                                 ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.accountMapper = accountMapper;
        this.transactionMapper = transactionMapper;
        this.positionMapper = positionMapper;
        this.cashBalanceMapper = cashBalanceMapper;
        this.cashLedgerMapper = cashLedgerMapper;
        this.importBatchMapper = importBatchMapper;
        this.syncBatchMapper = syncBatchMapper;
        this.planMapper = planMapper;
        this.exchangeRateMapper = exchangeRateMapper;
        this.tradingCalendar = tradingCalendar;
        this.runtimeProperties = runtimeProperties;
        this.dataQualityService = dataQualityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public InvestmentOverviewResponse overview(Long userId) {
        InvestmentOverviewResponse response = new InvestmentOverviewResponse();
        List<InvestmentPositionView> positions = listPositions(userId, null, null, null);
        List<Long> regularAccountIds = listAccounts(userId).stream().map(InvestmentAccount::getId).toList();
        List<InvestmentCashBalance> cash = regularAccountIds.isEmpty() ? List.of()
                : cashBalanceMapper.selectList(new LambdaQueryWrapper<InvestmentCashBalance>()
                .eq(InvestmentCashBalance::getUserId, userId)
                .in(InvestmentCashBalance::getAccountId, regularAccountIds));
        BigDecimal marketValue = positions.stream().map(InvestmentPositionView::getMarketValueCny)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cnyCash = cash.stream().filter(item -> "CNY".equals(item.getCurrency()))
                .map(InvestmentCashBalance::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netInvestment = cashLedgerMapper.selectList(new LambdaQueryWrapper<InvestmentCashLedger>()
                        .eq(InvestmentCashLedger::getUserId, userId)
                        .eq(InvestmentCashLedger::getExternalFlow, 1)).stream()
                .map(this::externalFlowCny).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPnl = positions.stream().map(item -> {
            BigDecimal rate = storedFxRate(item.getCurrency(), item.getDataDate());
            BigDecimal realizedCny = rate == null ? BigDecimal.ZERO : zero(item.getRealizedPnl()).multiply(rate);
            return zero(item.getUnrealizedPnlCny()).add(realizedCny);
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setPositions(positions);
        response.setTotalAssetCny(marketValue.add(cnyCash));
        response.setNetInvestmentCny(netInvestment);
        response.setTotalPnlCny(totalPnl);
        response.setDataDate(positions.stream().map(InvestmentPositionView::getDataDate)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null));
        response.setCashBalances(cash.stream().map(item -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("accountId", item.getAccountId());
            map.put("currency", item.getCurrency());
            map.put("balance", item.getBalance());
            return map;
        }).toList());
        InvestmentSyncBatch latest = syncBatchMapper.selectOne(new LambdaQueryWrapper<InvestmentSyncBatch>()
                .eq(InvestmentSyncBatch::getUserId, userId).orderByDesc(InvestmentSyncBatch::getId).last("LIMIT 1"));
        response.setSyncStatus(latest == null ? "NOT_SYNCED" : latest.getStatus());
        return response;
    }

    @Override
    public List<InvestmentAccount> listAccounts(Long userId) {
        return accountMapper.selectList(new LambdaQueryWrapper<InvestmentAccount>()
                .eq(InvestmentAccount::getUserId, userId)
                .ne(InvestmentAccount::getAccountType, "PAPER")
                .orderByAsc(InvestmentAccount::getCreatedAt));
    }

    @Override
    @Transactional
    public InvestmentAccount createAccount(Long userId, InvestmentAccountRequest request) {
        InvestmentAccount account = new InvestmentAccount();
        account.setUserId(userId);
        account.setAccountName(clean(request.getAccountName()));
        account.setAccountType(clean(request.getAccountType()).toUpperCase(Locale.ROOT));
        account.setBaseCurrency(currency(request.getBaseCurrency()));
        account.setDeleted(0);
        accountMapper.insert(account);
        if (request.getOpeningCash() != null && request.getOpeningCash().signum() != 0) {
            InvestmentTransactionRequest deposit = new InvestmentTransactionRequest();
            deposit.setAccountId(account.getId());
            deposit.setEventType(request.getOpeningCash().signum() > 0 ? "DEPOSIT" : "WITHDRAWAL");
            deposit.setTradeDate(LocalDate.now());
            deposit.setCurrency(account.getBaseCurrency());
            deposit.setAmount(request.getOpeningCash().abs());
            deposit.setNote("账户期初现金");
            addTransaction(userId, deposit);
        }
        return account;
    }

    @Override
    public List<InvestmentProduct> searchProducts(String keyword, String market, String productType) {
        LambdaQueryWrapper<InvestmentProduct> query = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(InvestmentProduct::getCode, keyword.trim())
                    .or().like(InvestmentProduct::getName, keyword.trim()));
        }
        if (market != null && !market.isBlank()) {
            query.eq(InvestmentProduct::getMarket, market.trim().toUpperCase(Locale.ROOT));
        }
        if (productType != null && !productType.isBlank()) {
            query.eq(InvestmentProduct::getProductType, productType.trim().toUpperCase(Locale.ROOT));
        }
        return productMapper.selectList(query.orderByAsc(InvestmentProduct::getMarket, InvestmentProduct::getCode)
                .last("LIMIT " + runtimeProperties.getApi().getProductSearchLimit()));
    }

    @Override
    public List<InvestmentPlan> listPlans(Long userId) {
        List<InvestmentPlan> plans = planMapper.selectList(new LambdaQueryWrapper<InvestmentPlan>()
                .eq(InvestmentPlan::getUserId, userId)
                .orderByDesc(InvestmentPlan::getEnabled)
                .orderByAsc(InvestmentPlan::getNextExecutionDate));
        plans.forEach(plan -> {
            LocalDate normalizedDate = tradingCalendar.nextOrSameTradingDay(plan.getNextExecutionDate());
            if (!normalizedDate.equals(plan.getNextExecutionDate())) {
                plan.setNextExecutionDate(normalizedDate);
                plan.setUpdatedAt(LocalDateTime.now());
                planMapper.updateById(plan);
            }
            InvestmentProduct product = productMapper.selectById(plan.getProductId());
            if (product != null) {
                plan.setProductName(product.getName());
                plan.setProductCode(product.getCode());
                plan.setMarket(product.getMarket());
            }
        });
        return plans;
    }

    @Override
    @Transactional
    public InvestmentPlan createPlan(Long userId, InvestmentPlanRequest request) {
        requireAccount(userId, request.getAccountId());
        String frequency = clean(request.getFrequency()).toUpperCase(Locale.ROOT);
        if (!Set.of("DAILY", "WEEKLY", "MONTHLY").contains(frequency)) {
            throw new IllegalArgumentException("定投频率仅支持 DAILY、WEEKLY 或 MONTHLY");
        }
        Integer maxDay = runtimeProperties.getPlan().getExecutionDayMaximums().get(frequency);
        if (maxDay == null) {
            throw new IllegalArgumentException("未配置定投频率 " + frequency + " 的执行日规则");
        }
        if (request.getExecutionDay() < 1 || request.getExecutionDay() > maxDay) {
            throw new IllegalArgumentException("定投执行日超出允许范围");
        }
        InvestmentPlan plan = new InvestmentPlan();
        plan.setUserId(userId);
        plan.setAccountId(request.getAccountId());
        plan.setProductId(requireProduct(request.getProduct()).getId());
        plan.setAmount(request.getAmount());
        plan.setCurrency(currency(request.getCurrency()));
        plan.setFrequency(frequency);
        plan.setExecutionDay(request.getExecutionDay());
        plan.setNextExecutionDate(tradingCalendar.nextOrSameTradingDay(
                Objects.requireNonNull(request.getNextExecutionDate(), "下次计划日期不能为空")));
        plan.setEnabled(1);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(plan.getCreatedAt());
        planMapper.insert(plan);
        return plan;
    }

    @Override
    @Transactional
    public InvestmentPlan setPlanEnabled(Long userId, Long planId, boolean enabled) {
        InvestmentPlan plan = planMapper.selectById(planId);
        if (plan == null || !Objects.equals(plan.getUserId(), userId)) {
            throw new IllegalArgumentException("定投计划不存在");
        }
        plan.setEnabled(enabled ? 1 : 0);
        if (enabled) {
            plan.setNextExecutionDate(tradingCalendar.nextOrSameTradingDay(plan.getNextExecutionDate()));
        }
        plan.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(plan);
        return plan;
    }

    @Override
    public List<InvestmentPositionView> listPositions(Long userId, Long accountId, String market, String productType) {
        LambdaQueryWrapper<InvestmentPosition> query = new LambdaQueryWrapper<InvestmentPosition>()
                .eq(InvestmentPosition::getUserId, userId)
                .gt(InvestmentPosition::getQuantity, BigDecimal.ZERO)
                .orderByDesc(InvestmentPosition::getMarketValueCny);
        if (accountId != null) {
            query.eq(InvestmentPosition::getAccountId, accountId);
        } else {
            List<Long> regularAccountIds = listAccounts(userId).stream().map(InvestmentAccount::getId).toList();
            if (regularAccountIds.isEmpty()) return List.of();
            query.in(InvestmentPosition::getAccountId, regularAccountIds);
        }
        Map<Long, InvestmentAccount> accounts = listAccounts(userId).stream()
                .collect(Collectors.toMap(InvestmentAccount::getId, item -> item));
        List<InvestmentPositionView> result = new ArrayList<>();
        for (InvestmentPosition position : positionMapper.selectList(query)) {
            InvestmentProduct product = productMapper.selectById(position.getProductId());
            if (product == null || (market != null && !market.isBlank() && !market.equalsIgnoreCase(product.getMarket()))
                    || (productType != null && !productType.isBlank() && !productType.equalsIgnoreCase(product.getProductType()))) {
                continue;
            }
            InvestmentPositionView view = new InvestmentPositionView();
            view.setId(position.getId());
            view.setAccountId(position.getAccountId());
            view.setAccountName(accounts.containsKey(position.getAccountId()) ? accounts.get(position.getAccountId()).getAccountName() : "-");
            view.setProductId(product.getId());
            view.setProductType(product.getProductType());
            view.setMarket(product.getMarket());
            view.setCode(product.getCode());
            view.setName(product.getName());
            view.setCurrency(product.getCurrency());
            view.setQuantity(position.getQuantity());
            view.setCostAmount(position.getCostAmount());
            view.setAverageCost(position.getAverageCost());
            view.setRealizedPnl(position.getRealizedPnl());
            view.setLatestPrice(position.getLatestPrice());
            view.setMarketValueCny(position.getMarketValueCny());
            view.setUnrealizedPnlCny(position.getUnrealizedPnlCny());
            view.setDataDate(position.getDataDate());
            result.add(view);
        }
        return result;
    }

    @Override
    public List<InvestmentTransaction> listTransactions(Long userId, Long accountId, int limit) {
        int resultLimit = Math.max(1, Math.min(limit,
                runtimeProperties.getApi().getMaxTransactionLimit()));
        LambdaQueryWrapper<InvestmentTransaction> query = new LambdaQueryWrapper<InvestmentTransaction>()
                .eq(InvestmentTransaction::getUserId, userId)
                .orderByDesc(InvestmentTransaction::getTradeDate, InvestmentTransaction::getId)
                .last("LIMIT " + resultLimit);
        if (accountId != null) {
            query.eq(InvestmentTransaction::getAccountId, accountId);
        }
        return transactionMapper.selectList(query);
    }

    @Override
    @Transactional
    public InvestmentTransaction addTransaction(Long userId, InvestmentTransactionRequest request) {
        String eventType = clean(request.getEventType()).toUpperCase(Locale.ROOT);
        if (!EVENTS.contains(eventType)) {
            throw new IllegalArgumentException("不支持的投资流水类型：" + eventType);
        }
        if ("REVERSAL".equals(eventType)) {
            if (request.getReversalTransactionId() == null) {
                throw new IllegalArgumentException("冲正必须指定原流水");
            }
            return reverseTransaction(userId, request.getReversalTransactionId(), request.getNote());
        }
        if ("CORRECTION".equals(eventType)) {
            throw new IllegalArgumentException("更正请先冲正原流水，再提交新的正确流水");
        }
        InvestmentAccount account = requireAccount(userId, request.getAccountId());
        InvestmentProduct product = PRODUCT_EVENTS.contains(eventType) ? requireProduct(request.getProduct()) : null;
        InvestmentTransaction transaction = new InvestmentTransaction();
        transaction.setUserId(userId);
        transaction.setAccountId(account.getId());
        transaction.setProductId(product == null ? null : product.getId());
        transaction.setEventType(eventType);
        transaction.setTradeDate(Objects.requireNonNull(request.getTradeDate(), "交易日期不能为空"));
        transaction.setSettlementDate(request.getSettlementDate());
        transaction.setCurrency(currency(request.getCurrency() == null ? account.getBaseCurrency() : request.getCurrency()));
        transaction.setQuantity(request.getQuantity());
        transaction.setPrice(request.getPrice());
        transaction.setAmount(request.getAmount());
        transaction.setFee(zero(request.getFee()));
        transaction.setFactor(request.getFactor());
        transaction.setSource(defaultText(request.getSource(), "MANUAL").toUpperCase(Locale.ROOT));
        transaction.setExternalRef(blankToNull(request.getExternalRef()));
        transaction.setNote(blankToNull(request.getNote()));
        validateEvent(transaction);
        transactionMapper.insert(transaction);
        applyCashProjection(transaction);
        if (transaction.getProductId() != null) {
            rebuildPosition(userId, account.getId(), transaction.getProductId());
        }
        return transaction;
    }

    @Override
    @Transactional
    public InvestmentTransaction reverseTransaction(Long userId, Long transactionId, String note) {
        InvestmentTransaction original = transactionMapper.selectById(transactionId);
        if (original == null || !userId.equals(original.getUserId())) {
            throw new IllegalArgumentException("原投资流水不存在");
        }
        Long count = transactionMapper.selectCount(new LambdaQueryWrapper<InvestmentTransaction>()
                .eq(InvestmentTransaction::getUserId, userId)
                .eq(InvestmentTransaction::getEventType, "REVERSAL")
                .eq(InvestmentTransaction::getReversalTransactionId, transactionId));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("该流水已经冲正");
        }
        InvestmentTransaction reversal = new InvestmentTransaction();
        reversal.setUserId(userId);
        reversal.setAccountId(original.getAccountId());
        reversal.setProductId(original.getProductId());
        reversal.setEventType("REVERSAL");
        reversal.setTradeDate(LocalDate.now());
        reversal.setCurrency(original.getCurrency());
        reversal.setSource("CORRECTION");
        reversal.setReversalTransactionId(original.getId());
        reversal.setNote(defaultText(note, "冲正流水 #" + original.getId()));
        reversal.setFee(BigDecimal.ZERO);
        transactionMapper.insert(reversal);
        InvestmentCashLedger originalCash = cashLedgerMapper.selectOne(new LambdaQueryWrapper<InvestmentCashLedger>()
                .eq(InvestmentCashLedger::getTransactionId, original.getId()));
        if (originalCash != null) {
            applyCashDelta(reversal, originalCash.getAmount().negate(), originalCash.getExternalFlow());
        }
        if (original.getProductId() != null) {
            rebuildPosition(userId, original.getAccountId(), original.getProductId());
        }
        return reversal;
    }

    @Override
    @Transactional
    public InvestmentImportPreviewResponse previewImport(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 CSV 文件");
        }
        long maxBytes = runtimeProperties.getApi().getImportMaxBytes();
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("CSV 文件不能超过配置上限 " + maxBytes + " 字节");
        }
        try {
            List<InvestmentImportRow> rows = csvParser.parse(file.getBytes());
            InvestmentImportBatch batch = new InvestmentImportBatch();
            batch.setUserId(userId);
            batch.setOriginalFilename(defaultText(file.getOriginalFilename(), "investment.csv"));
            batch.setStatus("PREVIEW");
            batch.setPayload(objectMapper.writeValueAsString(rows));
            batch.setRowCount(rows.size());
            batch.setErrorCount((int) rows.stream().filter(row -> !row.isValid()).count());
            importBatchMapper.insert(batch);
            return new InvestmentImportPreviewResponse(batch.getId(), batch.getOriginalFilename(),
                    batch.getRowCount(), batch.getErrorCount(), rows);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("CSV 解析失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional
    public Map<String, Object> commitImport(Long userId, InvestmentImportCommitRequest request) {
        InvestmentImportBatch batch = importBatchMapper.selectById(request.getBatchId());
        if (batch == null || !userId.equals(batch.getUserId())) {
            throw new IllegalArgumentException("导入预览不存在");
        }
        if (!"PREVIEW".equals(batch.getStatus())) {
            throw new IllegalArgumentException("该导入批次已处理");
        }
        try {
            List<InvestmentImportRow> rows = objectMapper.readValue(batch.getPayload(), new TypeReference<>() {});
            Set<Integer> selected = request.getRowNumbers() == null || request.getRowNumbers().isEmpty()
                    ? rows.stream().filter(InvestmentImportRow::isValid).map(InvestmentImportRow::getRowNumber).collect(Collectors.toSet())
                    : new HashSet<>(request.getRowNumbers());
            int imported = 0;
            for (InvestmentImportRow row : rows) {
                if (selected.contains(row.getRowNumber())) {
                    if (!row.isValid()) {
                        throw new IllegalArgumentException("第 " + row.getRowNumber() + " 行存在错误，不能提交");
                    }
                    addTransaction(userId, row.getTransaction());
                    imported++;
                }
            }
            batch.setStatus("COMMITTED");
            importBatchMapper.updateById(batch);
            return Map.of("batchId", batch.getId(), "imported", imported, "status", batch.getStatus());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("提交导入失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public Map<String, Object> analysis(Long userId) {
        List<InvestmentPositionView> positions = listPositions(userId, null, null, null);
        BigDecimal total = positions.stream().map(InvestmentPositionView::getMarketValueCny)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal max = positions.stream().map(InvestmentPositionView::getMarketValueCny)
                .filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal concentration = total.signum() == 0 ? BigDecimal.ZERO
                : max.divide(total, 4, RoundingMode.HALF_UP);
        Map<String, BigDecimal> marketExposure = new LinkedHashMap<>();
        for (InvestmentPositionView position : positions) {
            marketExposure.merge(position.getMarket(), zero(position.getMarketValueCny()), BigDecimal::add);
        }
        List<String> riskNotes = new ArrayList<>();
        BigDecimal concentrationLimit = runtimeProperties.getRisk().getPortfolioConcentrationWarningRatio();
        if (concentration.compareTo(concentrationLimit) > 0) {
            String limitPercent = concentrationLimit.movePointRight(2).stripTrailingZeros().toPlainString();
            riskNotes.add("单一持仓占比超过 " + limitPercent + "%，请关注集中度风险");
        }
        if (positions.stream().anyMatch(item -> item.getDataDate() == null)) {
            riskNotes.add("部分持仓缺少行情日期，暂不形成高置信度判断");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMarketValueCny", total);
        result.put("largestPositionWeight", concentration);
        result.put("marketExposure", marketExposure);
        result.put("riskNotes", riskNotes);
        result.put("dataDate", positions.stream().map(InvestmentPositionView::getDataDate)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null));
        result.put("calculationVersion", runtimeProperties.getParameterVersion());
        return result;
    }

    @Override
    public List<Map<String, Object>> dataQuality(Long userId) {
        return listPositions(userId, null, null, null).stream()
                .map(InvestmentPositionView::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .map(productMapper::selectById)
                .filter(Objects::nonNull)
                .map(dataQualityService::latestStatus)
                .toList();
    }

    @Override
    public List<Map<String, Object>> recommendations(Long userId) {
        Map<String, Object> analysis = analysis(userId);
        @SuppressWarnings("unchecked") List<String> risks = (List<String>) analysis.get("riskNotes");
        if (risks.isEmpty()) {
            return List.of(Map.of(
                    "type", "MONITOR", "conclusion", "当前未触发基础集中度规则",
                    "ruleVersion", runtimeProperties.getRisk().getPortfolioRuleVersion(), "evidence", analysis,
                    "riskNotes", List.of("该结果仅用于组合分析，不构成买卖建议")
            ));
        }
        return List.of(Map.of(
                "type", "RISK_ALERT", "conclusion", "组合存在需要关注的基础风险",
                "ruleVersion", runtimeProperties.getRisk().getPortfolioRuleVersion(), "evidence", analysis,
                "riskNotes", risks
        ));
    }

    @Override
    @Transactional
    public Map<String, Object> requestSync(Long userId) {
        InvestmentSyncBatch batch = new InvestmentSyncBatch();
        batch.setUserId(userId);
        batch.setJobType("HELD_PRODUCTS_DAILY");
        batch.setProvider("AUTO");
        batch.setStatus("PENDING");
        batch.setRowsSuccess(0);
        batch.setRowsFailed(0);
        batch.setStartedAt(LocalDateTime.now());
        syncBatchMapper.insert(batch);
        return Map.of("batchId", batch.getId(), "status", batch.getStatus(), "jobType", batch.getJobType());
    }

    private InvestmentAccount requireAccount(Long userId, Long accountId) {
        InvestmentAccount account = accountMapper.selectById(accountId);
        if (account == null || !userId.equals(account.getUserId()) || Integer.valueOf(1).equals(account.getDeleted())) {
            throw new IllegalArgumentException("投资账户不存在");
        }
        return account;
    }

    private InvestmentProduct requireProduct(InvestmentProductRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("该流水必须指定投资产品");
        }
        String productType = clean(request.getProductType()).toUpperCase(Locale.ROOT);
        String market = clean(request.getMarket()).toUpperCase(Locale.ROOT);
        String code = clean(request.getCode()).toUpperCase(Locale.ROOT);
        InvestmentProduct product = productMapper.selectOne(new LambdaQueryWrapper<InvestmentProduct>()
                .eq(InvestmentProduct::getProductType, productType)
                .eq(InvestmentProduct::getMarket, market)
                .eq(InvestmentProduct::getCode, code));
        if (product != null) {
            return product;
        }
        product = new InvestmentProduct();
        product.setProductType(productType);
        product.setMarket(market);
        product.setCode(code);
        product.setName(clean(request.getName()));
        product.setCurrency(currency(request.getCurrency()));
        product.setStatus("ACTIVE");
        productMapper.insert(product);
        return product;
    }

    private void validateEvent(InvestmentTransaction transaction) {
        switch (transaction.getEventType()) {
            case "BUY", "SELL", "DIVIDEND_REINVEST" -> {
                positive(transaction.getQuantity(), "数量");
                positive(transaction.getPrice(), "价格");
            }
            case "DIVIDEND", "DEPOSIT", "WITHDRAWAL", "FEE" -> positive(transaction.getAmount(), "金额");
            case "TRANSFER_IN", "TRANSFER_OUT" -> {
                positive(transaction.getQuantity(), "数量");
                if ("TRANSFER_IN".equals(transaction.getEventType())) {
                    positive(transaction.getPrice(), "转入成本价");
                }
            }
            case "SPLIT", "MERGE" -> positive(transaction.getFactor(), "拆分/合并因子");
            default -> { }
        }
    }

    private void applyCashProjection(InvestmentTransaction transaction) {
        BigDecimal delta = switch (transaction.getEventType()) {
            case "DEPOSIT" -> transaction.getAmount();
            case "WITHDRAWAL", "FEE" -> transaction.getAmount().negate();
            case "BUY", "DIVIDEND_REINVEST" -> transaction.getQuantity().multiply(transaction.getPrice()).add(transaction.getFee()).negate();
            case "SELL" -> transaction.getQuantity().multiply(transaction.getPrice()).subtract(transaction.getFee());
            case "DIVIDEND" -> transaction.getAmount();
            default -> BigDecimal.ZERO;
        };
        if (delta.signum() != 0) {
            int external = Set.of("DEPOSIT", "WITHDRAWAL").contains(transaction.getEventType()) ? 1 : 0;
            applyCashDelta(transaction, delta, external);
        }
    }

    private void applyCashDelta(InvestmentTransaction transaction, BigDecimal delta, int externalFlow) {
        InvestmentCashBalance balance = cashBalanceMapper.selectOne(new LambdaQueryWrapper<InvestmentCashBalance>()
                .eq(InvestmentCashBalance::getAccountId, transaction.getAccountId())
                .eq(InvestmentCashBalance::getCurrency, transaction.getCurrency()));
        if (balance == null) {
            balance = new InvestmentCashBalance();
            balance.setUserId(transaction.getUserId());
            balance.setAccountId(transaction.getAccountId());
            balance.setCurrency(transaction.getCurrency());
            balance.setBalance(delta);
            cashBalanceMapper.insert(balance);
        } else {
            balance.setBalance(balance.getBalance().add(delta));
            cashBalanceMapper.updateById(balance);
        }
        InvestmentCashLedger cash = new InvestmentCashLedger();
        cash.setUserId(transaction.getUserId());
        cash.setAccountId(transaction.getAccountId());
        cash.setTransactionId(transaction.getId());
        cash.setCurrency(transaction.getCurrency());
        cash.setEventType(transaction.getEventType());
        cash.setAmount(delta);
        cash.setExternalFlow(externalFlow);
        cash.setOccurredOn(transaction.getSettlementDate() == null ? transaction.getTradeDate() : transaction.getSettlementDate());
        cashLedgerMapper.insert(cash);
    }

    private void rebuildPosition(Long userId, Long accountId, Long productId) {
        List<InvestmentTransaction> transactions = transactionMapper.selectList(new LambdaQueryWrapper<InvestmentTransaction>()
                .eq(InvestmentTransaction::getUserId, userId)
                .eq(InvestmentTransaction::getAccountId, accountId)
                .eq(InvestmentTransaction::getProductId, productId)
                .orderByAsc(InvestmentTransaction::getTradeDate, InvestmentTransaction::getId));
        Set<Long> reversed = transactions.stream().filter(item -> "REVERSAL".equals(item.getEventType()))
                .map(InvestmentTransaction::getReversalTransactionId).filter(Objects::nonNull).collect(Collectors.toSet());
        LedgerState state = LedgerState.empty();
        for (InvestmentTransaction item : transactions) {
            if ("REVERSAL".equals(item.getEventType()) || reversed.contains(item.getId())) {
                continue;
            }
            state = applyPositionEvent(state, item);
        }
        InvestmentPosition position = positionMapper.selectOne(new LambdaQueryWrapper<InvestmentPosition>()
                .eq(InvestmentPosition::getAccountId, accountId)
                .eq(InvestmentPosition::getProductId, productId));
        if (position == null) {
            position = new InvestmentPosition();
            position.setUserId(userId);
            position.setAccountId(accountId);
            position.setProductId(productId);
        }
        position.setQuantity(state.quantity());
        position.setCostAmount(state.costAmount());
        position.setAverageCost(state.averageCost());
        position.setRealizedPnl(state.realizedPnl());
        InvestmentProduct product = productMapper.selectById(productId);
        BigDecimal fxRate = product == null ? null : storedFxRate(product.getCurrency(), position.getDataDate());
        if (position.getLatestPrice() != null && fxRate != null) {
            position.setMarketValueCny(position.getLatestPrice().multiply(position.getQuantity()).multiply(fxRate));
            position.setUnrealizedPnlCny(position.getMarketValueCny().subtract(position.getCostAmount().multiply(fxRate)));
        } else if (fxRate != null) {
            position.setMarketValueCny(position.getCostAmount().multiply(fxRate));
            position.setUnrealizedPnlCny(BigDecimal.ZERO);
        } else {
            position.setMarketValueCny(null);
            position.setUnrealizedPnlCny(null);
        }
        if (position.getId() == null) {
            positionMapper.insert(position);
        } else {
            positionMapper.updateById(position);
        }
    }

    private LedgerState applyPositionEvent(LedgerState state, InvestmentTransaction item) {
        return switch (item.getEventType()) {
            case "BUY", "DIVIDEND_REINVEST" -> calculator.apply(state, LedgerEvent.buy(item.getQuantity(), item.getPrice(), item.getFee()));
            case "SELL" -> calculator.apply(state, LedgerEvent.sell(item.getQuantity(), item.getPrice(), item.getFee()));
            case "DIVIDEND" -> calculator.apply(state, LedgerEvent.dividend(item.getAmount()));
            case "TRANSFER_IN" -> calculator.apply(state, LedgerEvent.buy(item.getQuantity(), item.getPrice(), BigDecimal.ZERO));
            case "TRANSFER_OUT" -> calculator.apply(state, LedgerEvent.sell(item.getQuantity(), state.averageCost(), BigDecimal.ZERO));
            case "SPLIT", "MERGE" -> calculator.apply(state, LedgerEvent.split(item.getFactor()));
            default -> state;
        };
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal externalFlowCny(InvestmentCashLedger flow) {
        BigDecimal rate = storedFxRate(flow.getCurrency(), flow.getOccurredOn());
        return rate == null ? BigDecimal.ZERO : flow.getAmount().multiply(rate);
    }

    private BigDecimal storedFxRate(String baseCurrency, LocalDate onOrBefore) {
        if ("CNY".equals(baseCurrency)) return BigDecimal.ONE;
        LambdaQueryWrapper<DailyExchangeRate> query = new LambdaQueryWrapper<DailyExchangeRate>()
                .eq(DailyExchangeRate::getBaseCurrency, baseCurrency)
                .eq(DailyExchangeRate::getQuoteCurrency, "CNY")
                .orderByDesc(DailyExchangeRate::getRateDate)
                .last("LIMIT 1");
        if (onOrBefore != null) query.le(DailyExchangeRate::getRateDate, onOrBefore);
        DailyExchangeRate rate = exchangeRateMapper.selectOne(query);
        return rate == null ? null : rate.getRate();
    }

    private static String currency(String value) {
        String result = defaultText(value, "CNY").toUpperCase(Locale.ROOT);
        if (result.length() != 3) {
            throw new IllegalArgumentException("币种必须使用三位 ISO 代码");
        }
        return result;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("必填文本不能为空");
        }
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + "必须大于 0");
        }
    }
}
