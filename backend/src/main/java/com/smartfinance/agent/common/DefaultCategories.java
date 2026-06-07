package com.smartfinance.agent.common;

import com.smartfinance.agent.entity.ExpenseCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认消费分类定义
 * 新用户注册时自动初始化这些分类
 */
public final class DefaultCategories {

    private DefaultCategories() {}

    /**
     * 获取默认分类列表（支出 + 收入）
     */
    public static List<ExpenseCategory> getDefaults() {
        List<ExpenseCategory> categories = new ArrayList<>();

        // 支出类
        add(categories, "餐饮", "food", 1);
        add(categories, "交通", "transport", 2);
        add(categories, "购物", "shopping", 3);
        add(categories, "住房", "home", 4);
        add(categories, "娱乐", "entertainment", 5);
        add(categories, "医疗", "medical", 6);
        add(categories, "教育", "education", 7);
        add(categories, "通讯", "communication", 8);
        add(categories, "人情", "social", 9);
        add(categories, "金融", "finance", 10);
        add(categories, "宠物", "pet", 11);
        add(categories, "日常", "daily", 12);

        // 收入类
        add(categories, "工资", "salary", 13);
        add(categories, "兼职", "parttime", 14);
        add(categories, "投资收益", "investment", 15);
        add(categories, "退款", "refund", 16);

        // 其他
        add(categories, "其他", "other", 99);

        return categories;
    }

    private static void add(List<ExpenseCategory> list, String name, String icon, int order) {
        ExpenseCategory cat = new ExpenseCategory();
        cat.setName(name);
        cat.setIcon(icon);
        cat.setSortOrder(order);
        list.add(cat);
    }
}
