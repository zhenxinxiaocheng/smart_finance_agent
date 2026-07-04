package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.service.PendingActionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentScheduleTool {

    private final ObjectProvider<PendingActionService> pendingActionServiceProvider;

    public AgentScheduleTool(ObjectProvider<PendingActionService> pendingActionServiceProvider) {
        this.pendingActionServiceProvider = pendingActionServiceProvider;
    }

    public String createAgentSchedule(String name,
                                      String description,
                                      String cronExpression,
                                      String taskQuery,
                                      String timezone) {
        try {
            Long userId = UserIdContext.get();
            if (userId == null) {
                return "Error: no user context";
            }
            PendingActionService pendingActionService = pendingActionServiceProvider.getObject();
            PendingAction action = pendingActionService.prepareSchedule(
                    userId,
                    name,
                    description,
                    cronExpression,
                    taskQuery,
                    timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone);
            return "已生成周期任务待确认：%s（ID: %s）。确认后会按 %s 定时执行。"
                    .formatted(action.getTitle(), action.getId(), cronExpression);
        } catch (Exception e) {
            log.warn("Failed to prepare agent schedule", e);
            return "创建周期任务待确认动作失败：" + e.getMessage();
        }
    }
}
