package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.BudgetAlert;
import com.smartfinance.agent.mapper.BudgetAlertMapper;
import com.smartfinance.agent.mapper.UserMapper;
import com.smartfinance.agent.service.BudgetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 财务主动监控器。
 * 定时检查用户预算执行情况，发现异常时自动生成预警。
 * 这是实现"主动干活"能力的关键组件。
 */
@Slf4j
@Component
public class FinancialMonitor {

    private final BudgetService budgetService;
    private final UserMapper userMapper;

    /**
     * 存储待推送给当前活跃用户的预警消息
     */
    private final CopyOnWriteArrayList<AlertNotification> pendingNotifications = new CopyOnWriteArrayList<>();

    public FinancialMonitor(BudgetService budgetService, UserMapper userMapper) {
        this.budgetService = budgetService;
        this.userMapper = userMapper;
    }

    /**
     * 每天凌晨0点和中午12点检查所有用户的预算执行情况
     */
    @Scheduled(cron = "0 0 0,12 * * ?")
    public void scheduledBudgetCheck() {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        log.info("Scheduled budget check started for month: {}", month);

        List<Long> allUserIds = userMapper.selectList(null).stream()
                .map(u -> u.getId())
                .toList();

        for (Long userId : allUserIds) {
            try {
                List<BudgetAlert> alerts = budgetService.checkBudgetAlerts(userId, month);
                if (!alerts.isEmpty()) {
                    pendingNotifications.add(new AlertNotification(userId,
                            "★ 你有 " + alerts.size() + " 条预算预警，说“查看预警”查看详情"));
                    log.info("Budget alerts generated for userId={}, count={}", userId, alerts.size());
                }
            } catch (Exception e) {
                log.warn("Budget check failed for userId={}: {}", userId, e.getMessage());
            }
        }
        log.info("Scheduled budget check completed");
    }

    /**
     * 每月1号执行月度健康检查
     */
    @Scheduled(cron = "0 0 10 1 * ?")
    public void scheduledMonthlyReview() {
        String lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        log.info("Scheduled monthly review started for month: {}", lastMonth);

        List<Long> allUserIds = userMapper.selectList(null).stream()
                .map(u -> u.getId())
                .toList();

        for (Long userId : allUserIds) {
            try {
                pendingNotifications.add(new AlertNotification(userId,
                        "★ 新的一个月开始了！我已经为你整理好了上月的财务报告，说“看看上月的报告”查看。"));
                log.info("Monthly review notification queued for userId={}", userId);
            } catch (Exception e) {
                log.warn("Monthly review failed for userId={}: {}", userId, e.getMessage());
            }
        }
    }

    /**
     * 获取用户的待处理预警
     */
    public String getPendingMessage(Long userId) {
        for (AlertNotification n : pendingNotifications) {
            if (n.userId.equals(userId)) {
                pendingNotifications.remove(n);
                return n.message;
            }
        }
        return null;
    }

    public boolean hasPendingAlerts(Long userId) {
        return pendingNotifications.stream().anyMatch(n -> n.userId.equals(userId));
    }

    private record AlertNotification(Long userId, String message) {}
}
