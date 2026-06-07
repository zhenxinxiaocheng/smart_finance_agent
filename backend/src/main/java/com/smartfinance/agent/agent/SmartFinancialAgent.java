package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.AnalysisRecord;
import com.smartfinance.agent.mapper.AnalysisRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmartFinancialAgent {
    private final FinancialAiService financialAiService;
    private final AgentVerifier agentVerifier;
    private final FinancialMonitor financialMonitor;
    private final AnalysisRecordMapper analysisRecordMapper;

    public SmartFinancialAgent(FinancialAiService f, AgentVerifier v, FinancialMonitor m, AnalysisRecordMapper r) {
        this.financialAiService = f; this.agentVerifier = v; this.financialMonitor = m; this.analysisRecordMapper = r;
    }

    public String process(Long userId, String userMessage) {
        StringBuilder fullResponse = new StringBuilder();
        if (financialMonitor.hasPendingAlerts(userId)) {
            String msg = financialMonitor.getPendingMessage(userId);
            if (msg != null) fullResponse.append(msg).append("\n\n---\n\n");
        }
        String agentResponse;
        try {
            UserIdContext.set(userId);
            agentResponse = financialAiService.chat(userId, userMessage);
        } catch (Exception e) {
            log.error("Agent failed: userId={}", userId, e);
            agentResponse = "Sorry, cannot process now.";
        } finally { UserIdContext.clear(); }
        fullResponse.append(agentResponse);
        try {
            AnalysisRecord record = new AnalysisRecord();
            record.setUserId(userId); record.setQuery(userMessage); record.setFinalAnswer(fullResponse.toString());
            analysisRecordMapper.insert(record);
        } catch (Exception e) { log.warn("Save failed: {}", e.getMessage()); }
        return fullResponse.toString();
    }
}