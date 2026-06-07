package com.smartfinance.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.ExecutionPlan;
import com.smartfinance.agent.dto.PlanStep;
import com.smartfinance.agent.dto.ReActResult;
import com.smartfinance.agent.dto.ReActStepRecord;
import com.smartfinance.agent.entity.AnalysisRecord;
import com.smartfinance.agent.mapper.AnalysisRecordMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ReActAgentService {

    private static final int MAX_STEPS = 6;
    private static final String FRIENDLY_ERROR = "抱歉，我现在暂时无法完成这次分析，请稍后再试。";
    private static final String MAX_STEP_ANSWER = "我已经做了几轮查询和分析，但这次任务还没有收敛到足够可靠的结论。你可以把问题缩小一点，比如指定月份、分类或要看的指标，我再继续帮你查。";

    private final ChatLanguageModel chatModel;
    private final ToolRegistry toolRegistry;
    private final AgentVerifier agentVerifier;
    private final FinancialMonitor financialMonitor;
    private final AnalysisRecordMapper analysisRecordMapper;
    private final ObjectMapper objectMapper;

    public ReActAgentService(ChatLanguageModel chatModel,
                             ToolRegistry toolRegistry,
                             AgentVerifier agentVerifier,
                             FinancialMonitor financialMonitor,
                             AnalysisRecordMapper analysisRecordMapper,
                             ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.agentVerifier = agentVerifier;
        this.financialMonitor = financialMonitor;
        this.analysisRecordMapper = analysisRecordMapper;
        this.objectMapper = objectMapper;
    }

    public ReActResult run(Long userId, String userMessage) {
        return run(userId, userMessage, ReActEventListener.NOOP);
    }

    public ReActResult run(Long userId, String userMessage, ReActEventListener listener) {
        String traceId = UUID.randomUUID().toString();
        List<ReActStepRecord> steps = new ArrayList<>();
        List<String> toolResults = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();

        messages.add(SystemMessage.from(systemPrompt()));

        if (financialMonitor.hasPendingAlerts(userId)) {
            String pending = financialMonitor.getPendingMessage(userId);
            if (pending != null && !pending.isBlank()) {
                messages.add(UserMessage.from("系统提醒：该用户有这些未处理的预算提醒，请在必要时纳入回答，但不要直接暴露内部字段。\n" + pending));
            }
        }

        messages.add(UserMessage.from("用户问题：" + userMessage));

        String finalAnswer = null;
        for (int stepNumber = 1; stepNumber <= MAX_STEPS; stepNumber++) {
            String raw = generate(messages);
            JsonNode decision = parseDecision(raw);

            if (decision == null) {
                messages.add(AiMessage.from(raw == null ? "" : raw));
                messages.add(UserMessage.from("你的上一条输出不是合法 JSON。请只输出一个 JSON 对象，格式必须是 action 或 final，不要包含 Markdown。"));
                continue;
            }

            String type = text(decision, "type");
            if ("final".equalsIgnoreCase(type)) {
                finalAnswer = text(decision, "answer");
                finalAnswer = verifyAndRepair(userMessage, finalAnswer, toolResults, messages);
                break;
            }

            if (!"action".equalsIgnoreCase(type)) {
                messages.add(UserMessage.from("type 只能是 action 或 final。请重新输出合法 JSON。"));
                continue;
            }

            String tool = text(decision, "tool");
            String summary = sanitizeSummary(text(decision, "summary"), tool);
            JsonNode input = decision.get("input");

            listener.onStepStarted(stepNumber, summary, tool);
            ToolRegistry.ToolObservation observation = toolRegistry.execute(tool, input, userId);
            listener.onStepFinished(stepNumber, observation.getSummary(), observation.isSuccess());

            ReActStepRecord record = ReActStepRecord.builder()
                    .stepNumber(stepNumber)
                    .summary(summary)
                    .tool(tool)
                    .input(toJson(input))
                    .success(observation.isSuccess())
                    .observationSummary(observation.getSummary())
                    .observation(observation.getRawResult())
                    .errorMessage(observation.isSuccess() ? null : observation.getSummary())
                    .build();
            steps.add(record);
            toolResults.add(observation.getRawResult());

            messages.add(AiMessage.from(toJson(decision)));
            messages.add(UserMessage.from("""
                    Observation:
                    success=%s
                    summary=%s
                    result=%s

                    请继续 ReAct。若已有足够信息，输出 final JSON；否则输出下一步 action JSON。
                    """.formatted(observation.isSuccess(), observation.getSummary(), observation.getRawResult())));
        }

        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = MAX_STEP_ANSWER;
        }

        saveAnalysis(userId, userMessage, traceId, steps, finalAnswer);
        listener.onFinal(finalAnswer, traceId);

        return ReActResult.builder()
                .traceId(traceId)
                .finalAnswer(finalAnswer)
                .steps(steps)
                .build();
    }

    private String generate(List<ChatMessage> messages) {
        try {
            AiMessage response = chatModel.generate(messages).content();
            return response == null ? "" : response.text();
        } catch (Exception e) {
            log.error("ReAct model call failed", e);
            return "{\"type\":\"final\",\"answer\":\"" + FRIENDLY_ERROR + "\"}";
        }
    }

    private JsonNode parseDecision(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return objectMapper.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("Invalid ReAct JSON: {}", raw);
            return null;
        }
    }

    private String verifyAndRepair(String userQuery, String answer, List<String> toolResults, List<ChatMessage> messages) {
        AgentVerifier.VerificationResult verification = agentVerifier.verify(userQuery, answer, toolResults);
        if (verification.passed()) {
            return answer;
        }
        messages.add(UserMessage.from("""
                你刚才的最终答案未通过校验：%s
                请基于已有 Observation 重新输出 final JSON，answer 要简洁、友好、不要编造。
                """.formatted(String.join(", ", verification.issues()))));
        JsonNode repaired = parseDecision(generate(messages));
        if (repaired != null && "final".equalsIgnoreCase(text(repaired, "type"))) {
            String repairedAnswer = text(repaired, "answer");
            if (!repairedAnswer.isBlank()) return repairedAnswer;
        }
        if (verification.correctedResponse() != null && !verification.correctedResponse().isBlank()) {
            return verification.correctedResponse();
        }
        return FRIENDLY_ERROR;
    }

    private void saveAnalysis(Long userId, String query, String traceId, List<ReActStepRecord> steps, String finalAnswer) {
        try {
            List<PlanStep> planSteps = steps.stream()
                    .map(step -> PlanStep.builder()
                            .stepNumber(step.getStepNumber())
                            .action("TOOL")
                            .description(step.getSummary())
                            .toolName(step.getTool())
                            .parameters(step.getInput())
                            .result(step.getObservationSummary())
                            .completed(step.isSuccess())
                            .errorMessage(step.getErrorMessage())
                            .build())
                    .toList();
            ExecutionPlan plan = ExecutionPlan.builder()
                    .originalQuery(query)
                    .steps(planSteps)
                    .currentStep(steps.size())
                    .status("COMPLETED")
                    .finalAnswer(finalAnswer)
                    .build();

            AnalysisRecord record = new AnalysisRecord();
            record.setUserId(userId);
            record.setQuery(query);
            record.setPlan(objectMapper.writeValueAsString(plan));
            record.setStepsResult(objectMapper.writeValueAsString(steps));
            record.setFinalAnswer(finalAnswer);
            analysisRecordMapper.insert(record);
        } catch (Exception e) {
            log.warn("Save ReAct analysis failed: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    private String systemPrompt() {
        LocalDate now = LocalDate.now();
        return """
                你是「智财Agent」的 ReAct 控制模型。当前日期：%s。

                你必须在每轮只输出一个严格 JSON 对象，不要输出 Markdown，不要解释格式，不要暴露 Thought。

                可输出两种 JSON：
                1) 调用工具：
                {"type":"action","summary":"给用户看的安全步骤摘要","tool":"工具名","input":{...}}
                2) 最终回答：
                {"type":"final","answer":"给用户看的最终中文回答"}

                规则：
                - summary 只能写安全摘要，例如“正在查询本月支出”，不要写内部推理或敏感信息。
                - 需要用户账单、预算、记账、实时财经信息时，先调用工具，不要编造数据。
                - 如果工具返回空数据，要诚实说明，并建议用户补录数据或缩小查询范围。
                - 最终答案用中文，简洁自然，默认不超过 500 字。
                - 不提供具体股票推荐，投资建议仅供参考。

                可用工具：
                %s
                """.formatted(now, toolRegistry.manifest());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String sanitizeSummary(String summary, String tool) {
        if (summary == null || summary.isBlank()) return "正在调用 " + tool;
        String clean = summary.replaceAll("[\\r\\n]+", " ").trim();
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public interface ReActEventListener {
        ReActEventListener NOOP = new ReActEventListener() {};

        default void onStepStarted(int stepNumber, String summary, String tool) {}

        default void onStepFinished(int stepNumber, String summary, boolean success) {}

        default void onFinal(String response, String traceId) {}
    }
}
