package com.smartfinance.agent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AgentVerifier {

    private static final Pattern HAS_AMOUNT_PATTERN = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern TOO_LONG_PATTERN = Pattern.compile("^.{500,}");

    public record VerificationResult(boolean passed, String correctedResponse, List<String> issues) {}

    public VerificationResult verify(String userQuery, String agentResponse, List<String> toolResults) {
        List<String> issues = new ArrayList<>();
        if (agentResponse == null || agentResponse.isBlank()) {
            issues.add("empty response");
            return new VerificationResult(false, "Sorry, cannot process.", issues);
        }
        if (TOO_LONG_PATTERN.matcher(agentResponse).find()) {
            issues.add("response too long");
        }
        boolean hasFailedTool = toolResults != null && toolResults.stream()
                .anyMatch(result -> result != null && (
                        result.contains("Exception")
                                || result.contains("工具执行失败")
                                || result.contains("Unknown tool")
                                || result.contains("Cannot get user info")
                                || result.contains("失败")));
        if (hasFailedTool && !(agentResponse.contains("暂时") || agentResponse.contains("失败") || agentResponse.contains("无法")
                || agentResponse.contains("没有") || agentResponse.contains("稍后") || agentResponse.contains("补录"))) {
            issues.add("tool failure not explained");
        }
        if (!issues.isEmpty()) {
            log.warn("Verifier found issues: {}", String.join(", ", issues));
            return new VerificationResult(false, trim(agentResponse), issues);
        }
        return new VerificationResult(true, agentResponse, issues);
    }

    private String trim(String response) {
        if (response == null) return "Sorry, cannot process.";
        return response.length() <= 500 ? response : response.substring(0, 500);
    }
}
