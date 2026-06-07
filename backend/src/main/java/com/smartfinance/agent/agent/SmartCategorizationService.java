package com.smartfinance.agent.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.entity.ExpenseCategory;
import com.smartfinance.agent.mapper.ExpenseCategoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能分类服务 - 基于数据库分类 + 关键词匹配的分类引擎
 *
 * 所有消费类型通过 ExpenseCategory 对象管理，不硬编码任何默认分类。
 * 关键词匹配基于分类名称和 benchmarkLabel 自动生成，无需预定义关键词库。
 */
@Slf4j
@Component
public class SmartCategorizationService {

    private final ExpenseCategoryMapper categoryMapper;

    public SmartCategorizationService(ExpenseCategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    // ===== 用户分类偏好 (userId -> (keyword -> category)) =====
    private final Map<Long, Map<String, String>> userPreferences = new ConcurrentHashMap<>();

    // ===== 用户分类计数 (userId -> (category -> count)) =====
    private final Map<Long, Map<String, Integer>> userCategoryCount = new ConcurrentHashMap<>();

    /**
     * 分类结果
     */
    public record CategorizationResult(
            String category,
            String type,
            double confidence,
            List<String> alternatives,
            String reason
    ) {}

    /**
     * 根据用户输入和交易类型，基于数据库中的分类进行智能匹配
     */
    public CategorizationResult categorize(String userMessage, String type, Long userId) {
        if (userMessage == null || userMessage.isBlank()) {
            return new CategorizationResult("其他", type, 0.3,
                    List.of(), "输入为空");
        }

        String normalizedMsg = userMessage.toLowerCase().trim();

        // 从数据库获取分类列表
        List<ExpenseCategory> dbCategories = loadCategories(userId);

        // 筛选出对应类型的分类
        List<ExpenseCategory> typeCategories = dbCategories.stream()
                .filter(c -> matchesType(c.getName(), type))
                .toList();

        if (typeCategories.isEmpty()) {
            return new CategorizationResult("其他", type, 0.3,
                    List.of(), "系统中暂无可用分类，请先在「管理分类」中创建");
        }

        // 动态构建关键词映射：分类名称 + benchmarkLabel 中的词组
        Map<String, List<String>> keywordMap = buildDynamicKeywords(typeCategories);

        // 匹配关键词
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (var entry : keywordMap.entrySet()) {
            String category = entry.getKey();
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (normalizedMsg.contains(keyword.toLowerCase())) {
                    score += keyword.length() >= 3 ? 3 : 1;
                }
            }
            if (score > 0) {
                scores.put(category, score);
            }
        }

        // 合并用户偏好
        Map<String, String> prefs = userPreferences.getOrDefault(0L, Map.of());
        for (var entry : scores.entrySet()) {
            if (prefs.containsKey(entry.getKey())) {
                scores.put(entry.getKey(), entry.getValue() + 2);
            }
        }

        if (scores.isEmpty()) {
            // 无法匹配关键词，返回第一个分类作为建议
            List<String> allNames = typeCategories.stream()
                    .map(ExpenseCategory::getName)
                    .toList();
            String defaultCategory = allNames.contains("其他") ? "其他" : allNames.get(0);
            return new CategorizationResult(defaultCategory, type, 0.3,
                    allNames, "未匹配到具体关键词，请选择分类");
        }

        // 按得分排序
        List<Map.Entry<String, Integer>> sorted = scores.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .toList();

        String bestCategory = sorted.get(0).getKey();
        int bestScore = sorted.get(0).getValue();
        double confidence = Math.min((double) bestScore / (bestScore + 2), 0.95);

        if (sorted.size() > 1 && sorted.get(1).getValue() >= bestScore - 1) {
            confidence = 0.55;
        }

        List<String> alternatives = sorted.stream()
                .skip(1)
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        String reason = String.format("匹配到关键词，最佳分类：%s（得分：%d）", bestCategory, bestScore);
        log.debug("分类结果: msg={}, type={}, category={}, confidence={}", userMessage, type, bestCategory, confidence);

        return new CategorizationResult(bestCategory, type, confidence, alternatives, reason);
    }

    /**
     * 获取所有可用分类名称
     */
    public List<String> getAvailableCategories(String type, Long userId) {
        return loadCategories(userId).stream()
                .filter(c -> matchesType(c.getName(), type))
                .map(ExpenseCategory::getName)
                .toList();
    }

    /**
     * 记录用户分类偏好
     */
    public void recordUserPreference(Long userId, String keyword, String category) {
        userPreferences.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(keyword.toLowerCase(), category);
        userCategoryCount.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .merge(category, 1, Integer::sum);
        log.info("已记录用户偏好: userId={}, keyword={}, category={}", userId, keyword, category);
    }

    /**
     * 获取用户最常用的分类
     */
    public List<String> getUserTopCategories(Long userId, String type, int limit) {
        Map<String, Integer> counts = userCategoryCount.getOrDefault(userId, Map.of());
        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 格式化分类列表为提示文本（供AI使用）
     */
    public String getCategoryPrompt(String type, Long userId) {
        List<ExpenseCategory> categories = loadCategories(userId).stream()
                .filter(c -> matchesType(c.getName(), type))
                .toList();
        if (categories.isEmpty()) {
            return type.equals("INCOME") ? "\n【可用收入分类】\n（暂无，请用户在「管理分类」中创建）\n"
                    : "\n【可用支出分类】\n（暂无，请用户在「管理分类」中创建）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(type.equals("INCOME") ? "\n【可用收入分类】\n" : "\n【可用支出分类】\n");
        for (ExpenseCategory c : categories) {
            sb.append("- ").append(c.getName());
            if (c.getBenchmarkLabel() != null && !c.getBenchmarkLabel().isBlank()) {
                sb.append("（").append(c.getBenchmarkLabel()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取所有分类的提示文本（供AI系统提示使用）
     */
    public String getAllCategoriesPrompt(Long userId) {
        List<ExpenseCategory> categories = loadCategories(userId);
        if (categories.isEmpty()) {
            return "（暂无分类，请引导用户在「管理分类」中创建消费分类）";
        }
        List<String> expense = new ArrayList<>();
        List<String> income = new ArrayList<>();
        for (ExpenseCategory c : categories) {
            if (isIncomeCategory(c.getName())) {
                income.add(c.getName());
            } else {
                expense.add(c.getName());
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!expense.isEmpty()) {
            sb.append("支出：").append(String.join("、", expense));
        }
        if (!income.isEmpty()) {
            if (!sb.isEmpty()) sb.append("；");
            sb.append("收入：").append(String.join("、", income));
        }
        return sb.toString();
    }

    // ===== 内部方法 =====

    /**
     * 从数据库加载指定用户的所有有效分类
     */
    private List<ExpenseCategory> loadCategories(Long userId) {
        LambdaQueryWrapper<ExpenseCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExpenseCategory::getUserId, userId)
                .eq(ExpenseCategory::getDeleted, 0)
                .orderByAsc(ExpenseCategory::getSortOrder);
        return categoryMapper.selectList(wrapper);
    }

    /**
     * 动态构建关键词映射：从分类名称和 benchmarkLabel 提取关键词
     */
    private Map<String, List<String>> buildDynamicKeywords(List<ExpenseCategory> categories) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (ExpenseCategory c : categories) {
            List<String> keywords = new ArrayList<>();
            // 分类名称本身作为关键词
            keywords.add(c.getName());
            // 从 benchmarkLabel 提取词组作为关键词
            if (c.getBenchmarkLabel() != null && !c.getBenchmarkLabel().isBlank()) {
                String label = c.getBenchmarkLabel();
                // 按 /、 分割出词组
                for (String part : label.split("[/\\s、，]+")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && trimmed.length() >= 2) {
                        keywords.add(trimmed);
                    }
                }
            }
            map.put(c.getName(), keywords);
        }
        return map;
    }

    /**
     * 判断分类名称是否属于指定类型
     * 收入特征：工资、薪、兼职、投资收益、退款、转账、收入、奖金、报酬
     * 支出特征：其他所有分类
     */
    private boolean matchesType(String categoryName, String type) {
        if ("INCOME".equals(type)) {
            return isIncomeCategory(categoryName);
        }
        return !isIncomeCategory(categoryName);
    }

    private boolean isIncomeCategory(String name) {
        if (name == null) return false;
        return name.contains("工资") || name.contains("薪") || name.contains("兼职")
                || name.contains("投资") || name.contains("收益") || name.contains("退款")
                || name.contains("转账") || name.contains("收入") || name.contains("奖金")
                || name.contains("报酬") || name.contains("分红") || name.contains("利息");
    }
}