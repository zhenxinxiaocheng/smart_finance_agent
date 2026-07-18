package com.smartfinance.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.context.ContextUsageSnapshot;
import com.smartfinance.agent.dto.ExecutionPlan;
import com.smartfinance.agent.dto.PlanStep;
import com.smartfinance.agent.dto.ReActResult;
import com.smartfinance.agent.dto.ReActStepRecord;
import com.smartfinance.agent.entity.AnalysisRecord;
import com.smartfinance.agent.mapper.AnalysisRecordMapper;
import com.smartfinance.agent.service.AgentMemoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private final AgentContextService agentContextService;
    private final AgentMemoryService agentMemoryService;
    private final MemoryExtractor memoryExtractor;
    private final ToolSelectionService toolSelectionService;

    public ReActAgentService(ChatLanguageModel chatModel,
                             ToolRegistry toolRegistry,
                             AgentVerifier agentVerifier,
                             FinancialMonitor financialMonitor,
                             AnalysisRecordMapper analysisRecordMapper,
                             ObjectMapper objectMapper,
                             AgentContextService agentContextService,
                             AgentMemoryService agentMemoryService,
                             MemoryExtractor memoryExtractor) {
        this(chatModel, toolRegistry, agentVerifier, financialMonitor, analysisRecordMapper, objectMapper,
                agentContextService, agentMemoryService, memoryExtractor, new ToolSelectionService(toolRegistry));
    }

    @Autowired
    public ReActAgentService(ChatLanguageModel chatModel,
                             ToolRegistry toolRegistry,
                             AgentVerifier agentVerifier,
                             FinancialMonitor financialMonitor,
                             AnalysisRecordMapper analysisRecordMapper,
                             ObjectMapper objectMapper,
                             AgentContextService agentContextService,
                             AgentMemoryService agentMemoryService,
                             MemoryExtractor memoryExtractor,
                             ToolSelectionService toolSelectionService) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.agentVerifier = agentVerifier;
        this.financialMonitor = financialMonitor;
        this.analysisRecordMapper = analysisRecordMapper;
        this.objectMapper = objectMapper;
        this.agentContextService = agentContextService;
        this.agentMemoryService = agentMemoryService;
        this.memoryExtractor = memoryExtractor;
        this.toolSelectionService = toolSelectionService;
    }

    public ReActResult run(Long userId, String userMessage) {
        return run(userId, userMessage, ReActEventListener.NOOP);
    }

    public ReActResult run(Long userId, String userMessage, List<com.smartfinance.agent.entity.ChatMessage> recentHistory) {
        return run(userId, userMessage, recentHistory, ReActEventListener.NOOP);
    }

    public ReActResult run(Long userId,
                           Long conversationId,
                           String userMessage,
                           List<com.smartfinance.agent.entity.ChatMessage> recentHistory) {
        return run(userId, conversationId, userMessage, recentHistory, ReActEventListener.NOOP);
    }

    public ReActResult run(Long userId, String userMessage, ReActEventListener listener) {
        return run(userId, userMessage, List.of(), listener);
    }

    public ReActResult run(Long userId,
                           String userMessage,
                           List<com.smartfinance.agent.entity.ChatMessage> recentHistory,
                           ReActEventListener listener) {
        return run(userId, null, userMessage, recentHistory, listener);
    }

    public ReActResult run(Long userId,
                           Long conversationId,
                           String userMessage,
                           List<com.smartfinance.agent.entity.ChatMessage> recentHistory,
                           ReActEventListener listener) {
        String traceId = UUID.randomUUID().toString();
        List<ReActStepRecord> steps = new ArrayList<>();
        List<String> toolResults = new ArrayList<>();
        Set<String> executedTools = new LinkedHashSet<>();
        List<ChatMessage> messages = new ArrayList<>();
        boolean usedTool = false;

        listener.onRunStarted(traceId);
        messages.add(SystemMessage.from(systemPromptV2(userId, userMessage)));

        if (financialMonitor.hasPendingAlerts(userId)) {
            String pending = financialMonitor.getPendingMessage(userId);
            if (pending != null && !pending.isBlank()) {
                messages.add(UserMessage.from("系统提醒：该用户有这些未处理的预算提醒，请在必要时纳入回答，但不要直接暴露内部字段。\n" + pending));
            }
        }

        AgentContextService.AgentContext agentContext = agentContextService.build(
                userId, userMessage, recentHistory, traceId, conversationId);
        messages.addAll(agentContext.messages());
        String languageInstruction = agentContext.languageInstruction();
        if (agentContext.contextBundle() != null) {
            listener.onContextUsage(agentContext.contextBundle().usage());
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
                ToolGateDecision gateDecision = requiredToolBeforeFinal(userId, userMessage, finalAnswer, executedTools);
                if (gateDecision.requiredTool() != null) {
                    messages.add(AiMessage.from(raw == null ? "" : raw));
                    messages.add(UserMessage.from(requiredToolCorrection(gateDecision, languageInstruction)));
                    continue;
                }
                finalAnswer = verifyAndRepair(userMessage, finalAnswer, toolResults, messages, languageInstruction);
                break;
            }

            if (!"action".equalsIgnoreCase(type)) {
                messages.add(UserMessage.from("type 只能是 action 或 final。请重新输出合法 JSON。"));
                continue;
            }

            String requestedTool = text(decision, "tool");
            String tool = toolRegistry.canonicalToolName(requestedTool);
            if (tool == null || tool.isBlank()) {
                tool = requestedTool;
            }
            String skill = text(decision, "skill");
            String summary = sanitizeSummary(text(decision, "summary"), tool);
            JsonNode input = decision.get("input");

            listener.onStepStarted(stepNumber, summary, tool);
            ToolRegistry.ToolObservation observation = toolRegistry.execute(tool, input, userId, traceId, skill);
            usedTool = true;
            executedTools.add(tool);
            listener.onStepFinished(stepNumber, summary, tool, toJson(input), observation.getSummary(),
                    observation.isSuccess(), observation.isSuccess() ? null : observation.getSummary());

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
            String toolRef = "traceId=%s, stepNumber=%d, tool=%s".formatted(traceId, stepNumber, tool);
            messages.add(UserMessage.from("""
                    Observation:
                    success=%s
                    summary=%s
                    ref=%s
                    完整结果已保存到运行轨迹；除非需要明细，否则仅根据 summary 和 ref 继续。

                    请继续 ReAct。若已有足够信息，输出 final JSON；否则输出下一步 action JSON。
                    %s
                    """.formatted(observation.isSuccess(), observation.getSummary(), toolRef, languageInstruction)));
        }

        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = MAX_STEP_ANSWER;
        }

        saveAnalysis(userId, userMessage, traceId, steps, finalAnswer);
        if (agentMemoryService.isAutoMemoryEnabled(userId)
                && !(usedTool && agentMemoryService.shouldSkipToolAssistedMemory(userId))) {
            extractMemory(userId, userMessage, finalAnswer);
        }
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

    private String verifyAndRepair(String userQuery,
                                   String answer,
                                   List<String> toolResults,
                                   List<ChatMessage> messages,
                                   String languageInstruction) {
        AgentVerifier.VerificationResult verification = agentVerifier.verify(userQuery, answer, toolResults);
        if (verification.passed() && followsLanguageInstruction(answer, languageInstruction)) {
            return answer;
        }
        if (!followsLanguageInstruction(answer, languageInstruction)) {
            messages.add(UserMessage.from("""
                    你的上一条 final answer 没有遵守语言要求。
                    %s
                    请基于已有 Observation 重新输出 final JSON，只改写 answer 的语言，不要改变事实和数字。
                    """.formatted(languageInstruction)));
            JsonNode languageRepaired = parseDecision(generate(messages));
            if (languageRepaired != null && "final".equalsIgnoreCase(text(languageRepaired, "type"))) {
                String repairedAnswer = text(languageRepaired, "answer");
                if (!repairedAnswer.isBlank()) return repairedAnswer;
            }
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
            record.setTraceId(traceId);
            record.setQuery(query);
            record.setPlan(objectMapper.writeValueAsString(plan));
            record.setStepsResult(objectMapper.writeValueAsString(steps));
            record.setFinalAnswer(finalAnswer);
            analysisRecordMapper.insert(record);
        } catch (Exception e) {
            log.warn("Save ReAct analysis failed: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    private void extractMemory(Long userId, String userMessage, String finalAnswer) {
        try {
            memoryExtractor.extractAndSave(userId, userMessage, finalAnswer);
        } catch (Exception e) {
            log.warn("Memory extraction failed: userId={}, error={}", userId, e.getMessage());
        }
    }

    private boolean followsLanguageInstruction(String answer, String languageInstruction) {
        if (languageInstruction == null || languageInstruction.isBlank()) {
            return true;
        }
        if (languageInstruction.contains("English")) {
            return answer != null && !answer.matches(".*[\\u4e00-\\u9fff].*");
        }
        return true;
    }

    private String systemPromptV2(Long userId, String userMessage) {
        LocalDate now = LocalDate.now();
        return """
                你具备短期对话记忆能力，可以参考最近的用户消息和助手最终回复来理解上下文。
                当用户说“刚才、之前、继续、上一个、那个”等表达时，优先结合最近对话历史判断含义；如果历史不足，再诚实说明无法确定。

                你是「智财Agent」的 ReAct 控制模型。当前日期：%s。

                你必须在每轮只输出一个严格 JSON 对象，不要输出 Markdown，不要解释格式，不要暴露 Thought。

                可输出两种 JSON：
                1) 调用工具：
                {"type":"action","summary":"给用户看的安全步骤摘要","skill":"可选Skill名","tool":"工具名","input":{...}}
                2) 最终回答：
                {"type":"final","answer":"给用户看的最终回答"}

                规则：
                - summary 只能写安全摘要，例如“正在查询本月支出”，不要写内部推理或敏感信息。
                - 如果你是根据某个 Skill 的说明决定调用工具，必须在 action JSON 中写入 "skill":"该 Skill 的 skill_key"；如果只是直接使用内置工具，可以省略 skill。
                - skill 不能替代 tool；实际执行仍只能调用 tool 字段里的安全工具。
                - 当用户明确要求“做成 Skill / 记成 Skill / 包装成 Skill / 以后遇到这类问题按这个流程做”时，调用 create_custom_skill 生成自定义 Skill 草稿；普通偏好不要自动做成 Skill。
                - 当用户明确要求“定期 / 每天 / 每周 / 每月 / 提醒我 / 自动帮我”执行某个财务分析、复盘或监控任务时，调用 create_agent_schedule 生成待确认周期任务；不要直接创建任务，不要把一次性偏好做成周期任务；input 必须包含 name、description、cronExpression、taskQuery、timezone。
                - 需要用户账单、预算、记账、实时财经信息时，先调用工具，不要编造数据。
                - 如果任务需要读取或改变系统数据、生成待确认动作、查询实时信息，必须先根据可用 Skills 输出 action；没有对应 Observation，不允许声称已经查询、创建、设置、记录或完成。
                - 如果工具返回空数据，要诚实说明，并建议用户补录数据或缩小查询范围。
                - 最终答案默认用中文，简洁自然，默认不超过 500 字；如果当前问题或 Agent 长期记忆指定了其他语言，必须使用指定语言。
                - 如果当前问题和 Agent 长期记忆的语言要求冲突，以当前问题为准。
                - 股票、基金、行业问题可以给出“偏看好、偏谨慎、可观察”等倾向性建议，但必须说明风险，不能承诺收益，不能使用“稳赚、一定上涨、立即买入”等确定性表达。
                - 涉及实时行情、新闻、政策、汇率、上市公司近期动态时，必须先调用 search_web，不要凭空编造实时信息。

                可用 Skills：
                %s
                """.formatted(now, toolSelectionService.manifest(userId, userMessage));
    }

    private ToolGateDecision requiredToolBeforeFinal(Long userId,
                                                     String userMessage,
                                                     String finalAnswer,
                                                     Set<String> executedTools) {
        if (!executedTools.isEmpty()) {
            return ToolGateDecision.none();
        }
        List<ChatMessage> gateMessages = List.of(
                SystemMessage.from("""
                        你是工具调用裁决器。你的任务是判断 ReAct 控制模型的 final answer 是否过早绕过了工具。
                        只输出 JSON，不要输出 Markdown。

                        输出格式：
                        {"requiresTool":true|false,"tool":"需要先调用的工具名，若不需要则为空","reason":"一句话原因"}

                        裁决规则：
                        - 如果 final answer 只是通用建议、澄清、解释，且不依赖系统私有数据、外部实时信息或写操作，requiresTool=false。
                        - 如果 final answer 声称已经查询、创建、设置、记录、删除、确认、导入、搜索或生成待确认动作，但当前尚未提供对应工具 Observation，requiresTool=true。
                        - 如果用户请求需要系统私有数据、外部实时信息或会改变系统状态，且 final answer 没有先基于工具 Observation 回答，requiresTool=true。
                        - tool 必须来自可用工具清单；根据工具描述和输入 schema 自行选择最匹配的工具。
                        """),
                UserMessage.from("""
                        用户原始问题：
                        %s

                        待检查的 final answer：
                        %s

                        当前已执行工具：
                        %s

                        可用工具清单：
                        %s
                        """.formatted(
                        userMessage == null ? "" : userMessage,
                        finalAnswer == null ? "" : finalAnswer,
                        String.join(", ", executedTools),
                        toolRegistry.manifest(userId)))
        );
        JsonNode decision = parseDecision(generate(gateMessages));
        if (decision == null || !decision.path("requiresTool").asBoolean(false)) {
            return ToolGateDecision.none();
        }
        String requestedTool = text(decision, "tool");
        String tool = toolRegistry.canonicalToolName(requestedTool);
        if (tool == null || tool.isBlank()) {
            tool = requestedTool;
        }
        if (tool == null || tool.isBlank() || executedTools.contains(tool)) {
            return ToolGateDecision.none();
        }
        return new ToolGateDecision(tool, text(decision, "reason"));
    }

    private String requiredToolCorrection(ToolGateDecision decision, String languageInstruction) {
        return """
                工具调用裁决器认为你的上一条 final answer 过早绕过了工具。
                请立即输出 action JSON 调用工具：%s。
                理由：%s
                input 必须根据用户原文、工具说明和 schema 填写；不要继续输出 final，也不要声称已经完成。
                %s
                """.formatted(decision.requiredTool(), decision.reason(), languageInstruction);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private record ToolGateDecision(String requiredTool, String reason) {
        private static ToolGateDecision none() {
            return new ToolGateDecision(null, "");
        }
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

        default void onStepFinished(int stepNumber,
                                    String summary,
                                    String tool,
                                    String input,
                                    String observationSummary,
                                    boolean success,
                                    String errorMessage) {
            onStepFinished(stepNumber, observationSummary, success);
        }

        default void onFinal(String response, String traceId) {}

        default void onRunStarted(String traceId) {}

        default void onContextUsage(ContextUsageSnapshot usage) {}
    }
}
