package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.entity.ExpenseCategory;
import com.smartfinance.agent.mapper.ExpenseCategoryMapper;
import com.smartfinance.agent.service.ExpenseCategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExpenseCategoryServiceImpl implements ExpenseCategoryService {

    private final ExpenseCategoryMapper expenseCategoryMapper;

    public ExpenseCategoryServiceImpl(ExpenseCategoryMapper expenseCategoryMapper) {
        this.expenseCategoryMapper = expenseCategoryMapper;
    }

    @Override
    public List<ExpenseCategory> listByUser(Long userId) {
        LambdaQueryWrapper<ExpenseCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(ExpenseCategory::getUserId, 0L)
                .or().eq(ExpenseCategory::getUserId, userId));
        wrapper.orderByAsc(ExpenseCategory::getSortOrder);
        wrapper.orderByAsc(ExpenseCategory::getId);
        return expenseCategoryMapper.selectList(wrapper);
    }

    @Override
    public ExpenseCategory getById(Long id, Long userId) {
        ExpenseCategory category = expenseCategoryMapper.selectById(id);
        if (category == null) {
            throw new IllegalArgumentException("消费类型不存在");
        }
        if (category.getUserId() != 0L && !category.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该消费类型");
        }
        return category;
    }

    @Override
    public ExpenseCategory add(Long userId, String name, String icon,
                               Integer benchmarkMin, Integer benchmarkMax,
                               String benchmarkLabel, Integer sortOrder) {
        ExpenseCategory category = new ExpenseCategory();
        category.setUserId(userId);
        category.setName(name);
        category.setIcon(icon);
        category.setBenchmarkMin(benchmarkMin);
        category.setBenchmarkMax(benchmarkMax);
        category.setBenchmarkLabel(benchmarkLabel);
        category.setSortOrder(sortOrder != null ? sortOrder : 0);
        expenseCategoryMapper.insert(category);
        return category;
    }

    @Override
    public ExpenseCategory update(Long id, Long userId, String name, String icon,
                                  Integer benchmarkMin, Integer benchmarkMax,
                                  String benchmarkLabel, Integer sortOrder) {
        ExpenseCategory existing = getById(id, userId);
        if (existing.getUserId() == 0L) {
            throw new IllegalArgumentException("系统默认分类不可修改");
        }
        existing.setName(name);
        existing.setIcon(icon);
        existing.setBenchmarkMin(benchmarkMin);
        existing.setBenchmarkMax(benchmarkMax);
        existing.setBenchmarkLabel(benchmarkLabel);
        existing.setSortOrder(sortOrder != null ? sortOrder : existing.getSortOrder());
        expenseCategoryMapper.updateById(existing);
        return existing;
    }

    @Override
    public void delete(Long id, Long userId) {
        ExpenseCategory existing = getById(id, userId);
        if (existing.getUserId() == 0L) {
            throw new IllegalArgumentException("系统默认分类不可删除");
        }
        expenseCategoryMapper.deleteById(id);
    }
}
