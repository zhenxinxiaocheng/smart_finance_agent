package com.smartfinance.agent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能分类服务 - 基于关键词匹配 + 用户偏好学习的分类引擎
 */
@Slf4j
@Component
public class SmartCategorizationService {

    // ===== 支出分类关键词库 =====
    private static final Map<String, List<String>> EXPENSE_KEYWORDS = new LinkedHashMap<>();
    static {
        // 餐饮食品
        EXPENSE_KEYWORDS.put("餐饮", List.of(
                "饭", "餐", "外卖", "食堂", "餐厅", "饭店", "火锅", "麻辣烫", "烧烤",
                "奶茶", "咖啡", "饮料", "冰淇淋", "甜品", "蛋糕", "面包", "零食",
                "小吃", "早餐", "午饭", "晚饭", "午餐", "晚餐", "夜宵", "聚餐",
                "菜", "米", "油", "调料", "水果", "买菜", "超市食品", "便利店"
        ));
        // 交通出行
        EXPENSE_KEYWORDS.put("交通", List.of(
                "地铁", "公交", "打车", "滴滴", "出租车", "网约车", "加油", "汽油",
                "停车", "过路费", "高速", "ETC", "火车", "高铁", "飞机", "机票",
                "共享单车", "骑行", "电动车", "充电", "油费", "通勤"
        ));
        // 购物消费
        EXPENSE_KEYWORDS.put("购物", List.of(
                "衣服", "裤子", "鞋子", "包", "化妆品", "护肤品", "口红", "面膜",
                "淘宝", "京东", "拼多多", "网购", "快递", "日用品", "洗漱",
                "洗发水", "纸巾", "家居", "电器", "手机", "电脑", "数码",
                "礼物", "玩具", "文具", "买", "购", "商场"
        ));
        // 住房居住
        EXPENSE_KEYWORDS.put("住房", List.of(
                "房租", "房贷", "物业", "水电", "电费", "水费", "燃气", "煤气",
                "网费", "宽带", "维修", "装修", "家具", "房租押金"
        ));
        // 医疗健康
        EXPENSE_KEYWORDS.put("医疗", List.of(
                "看病", "挂号", "药", "医院", "诊所", "体检", "牙科", "眼镜",
                "健身", "运动", "健身房", "保健品", "维生素", "保险", "医保"
        ));
        // 教育学习
        EXPENSE_KEYWORDS.put("教育", List.of(
                "学费", "培训", "课程", "书本", "教材", "考试", "报名", "网课",
                "学习", "资料", "文具", "打印", "辅导"
        ));
        // 娱乐休闲
        EXPENSE_KEYWORDS.put("娱乐", List.of(
                "电影", "KTV", "唱歌", "旅游", "门票", "景点", "酒店", "游戏",
                "充值", "视频会员", "音乐", "演出", "聚会", "酒吧", "剧本杀",
                "密室", "按摩", "洗浴", "SPA", "旅游团"
        ));
        // 通讯
        EXPENSE_KEYWORDS.put("通讯", List.of(
                "话费", "流量", "手机费", "宽带费", "套餐"
        ));
        // 人情往来
        EXPENSE_KEYWORDS.put("人情", List.of(
                "红包", "份子", "结婚", "生日礼物", "送礼", "请客", "捐款", "随礼"
        ));
        // 金融保险
        EXPENSE_KEYWORDS.put("金融", List.of(
                "理财", "基金", "股票", "保险", "保单", "手续费", "利息", "罚款",
                "信用卡还款", "花呗", "借呗"
        ));
        // 宠物
        EXPENSE_KEYWORDS.put("宠物", List.of(
                "猫粮", "狗粮", "猫砂", "宠物", "兽医", "驱虫", "洗澡宠物", "遛狗"
        ));
        // 其他
        EXPENSE_KEYWORDS.put("其他", List.of(
                "杂项", "其他", "零用"
        ));
    }

    // ===== 收入分类关键词库 =====
    private static final Map<String, List<String>> INCOME_KEYWORDS = new LinkedHashMap<>();
    static {
        INCOME_KEYWORDS.put("工资", List.of(
                "工资", "薪水", "薪资", "发工资", "基本工资", "绩效", "年终奖", "奖金"
        ));
        INCOME_KEYWORDS.put("兼职", List.of(
                "兼职", "副业", "外快", "接单", "稿费", "翻译", "家教", "咨询费"
        ));
        INCOME_KEYWORDS.put("投资收益", List.of(
                "利息", "股息", "分红", "理财收益", "基金收益", "股票收益", "房租收入", "租金"
        ));
        INCOME_KEYWORDS.put("退款", List.of(
                "退款", "退货", "报销", "返现", "优惠", "红包收入"
        ));
        INCOME_KEYWORDS.put("转账", List.of(
                "转账", "汇款", "收到", "转入", "借入", "还款收回"
        ));
        INCOME_KEYWORDS.put("其他收入", List.of(
                "红包", "礼金", "中奖", "奖金非工资"
        ));
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
     * 根据用户输入和交易类型推荐分类
     */
    public CategorizationResult categorize(String userMessage, String type) {
        if (userMessage == null || userMessage.isBlank()) {
            return new CategorizationResult("其他", type, 0.5,
                    List.of("其他"), "输入为空，默认归类为其他");
        }

        String normalizedMsg = userMessage.toLowerCase().trim();

        // 选择对应的关键词库
        Map<String, List<String>> keywordMap = "INCOME".equals(type) ? INCOME_KEYWORDS : EXPENSE_KEYWORDS;

        // 匹配关键词
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (var entry : keywordMap.entrySet()) {
            String category = entry.getKey();
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (normalizedMsg.contains(keyword)) {
                    // 长关键词匹配权重更高
                    score += keyword.length() >= 3 ? 3 : 1;
                }
            }
            if (score > 0) {
                scores.put(category, score);
            }
        }

        // 合并用户偏好
        Map<String, String> prefs = userPreferences.getOrDefault(0L, Map.of());
        // 用通用userId=0的全局偏好，后续可扩展到按用户
        for (var entry : scores.entrySet()) {
            String cat = entry.getKey();
            if (prefs.containsKey(cat)) {
                scores.put(cat, entry.getValue() + 2); // 偏好加成
            }
        }

        if (scores.isEmpty()) {
            // 根据金额和类型猜测
            if ("INCOME".equals(type)) {
                // 无法匹配关键词的收入默认归"其他收入"
                return new CategorizationResult("其他收入", type, 0.5,
                        new ArrayList<>(INCOME_KEYWORDS.keySet()), "未匹配到具体关键词，建议确认分类");
            }
            // 通过AI的类别描述再尝试
            return new CategorizationResult("其他", type, 0.4,
                    new ArrayList<>(EXPENSE_KEYWORDS.keySet()), "未匹配到具体关键词，建议选择分类");
        }

        // 按得分排序
        List<Map.Entry<String, Integer>> sorted = scores.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .toList();

        String bestCategory = sorted.get(0).getKey();
        int bestScore = sorted.get(0).getValue();
        double maxPossibleScore = bestScore + 2; // 考虑偏好加成
        double confidence = Math.min(bestScore / maxPossibleScore, 0.95);

        // 如果有多个接近的匹配，降低置信度
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
     * 获取所有可用分类
     */
    public List<String> getAvailableCategories(String type) {
        if ("INCOME".equals(type)) {
            return new ArrayList<>(INCOME_KEYWORDS.keySet());
        }
        return new ArrayList<>(EXPENSE_KEYWORDS.keySet());
    }

    /**
     * 记录用户分类偏好（学习用户习惯）
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
    public String getCategoryPrompt(String type) {
        Map<String, List<String>> keywordMap = "INCOME".equals(type) ? INCOME_KEYWORDS : EXPENSE_KEYWORDS;
        StringBuilder sb = new StringBuilder();
        sb.append(type.equals("INCOME") ? "\n【可用收入分类】\n" : "\n【可用支出分类】\n");
        for (var entry : keywordMap.entrySet()) {
            sb.append("- ").append(entry.getKey())
                    .append("：").append(String.join("、", entry.getValue().stream().limit(5).toList()))
                    .append("\n");
        }
        return sb.toString();
    }
}