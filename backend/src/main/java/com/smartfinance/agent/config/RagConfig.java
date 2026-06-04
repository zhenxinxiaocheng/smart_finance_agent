package com.smartfinance.agent.config;

import com.smartfinance.agent.agent.FinancialAiService;
import com.smartfinance.agent.agent.FinancialTools;
import com.smartfinance.agent.agent.TransactionRecorder;
import com.smartfinance.agent.agent.WebSearchTool;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Configuration
public class RagConfig {

    @Value("${langchain4j.dashscope.api-key}")
    private String apiKey;

    @Value("${langchain4j.dashscope.embedding-model.model-name}")
    private String embeddingModelName;

    @Bean
    public EmbeddingModel qwenEmbeddingModel() {
        QwenEmbeddingModel model = QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .build();
        return new BatchEmbeddingModel(model);
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public EmbeddingStoreIngestor embeddingStoreIngestor(EmbeddingModel embeddingModel,
                                                          EmbeddingStore<TextSegment> embeddingStore) {
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 50);
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingModel embeddingModel,
                                              EmbeddingStore<TextSegment> embeddingStore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.5)
                .build();
    }

    @Bean
    public List<Document> knowledgeDocuments() {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge");
            if (!resource.exists()) {
                log.warn("知识库目录不存在，跳过RAG文档加载");
                return List.of();
            }
            Path knowledgePath = resource.getFile().toPath();
            List<Document> documents = FileSystemDocumentLoader.loadDocuments(knowledgePath);
            log.info("加载了 {} 个知识文档", documents.size());
            return documents;
        } catch (IOException e) {
            log.error("加载知识文档失败", e);
            return List.of();
        }
    }

    @Bean
    @Lazy
    public FinancialAiService ragFinancialAiService(OpenAiChatModel chatModel,
                                                     FinancialTools financialTools,
                                                     TransactionRecorder transactionRecorder,
                                                     WebSearchTool webSearchTool,
                                                     ContentRetriever contentRetriever,
                                                     EmbeddingStoreIngestor ingestor,
                                                     List<Document> knowledgeDocuments) {
        if (!knowledgeDocuments.isEmpty()) {
            ingestor.ingest(knowledgeDocuments);
            log.info("RAG知识库初始化完成，已索引 {} 个文档", knowledgeDocuments.size());
        }

        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int month = now.getMonthValue();
        int day = now.getDayOfMonth();
        String systemPrompt = "你是「智财Agent」—— 一位专注于个人理财管理与消费行为分析的智能顾问。\n\n"
                + "【专业资质】\n"
                + "- 持有金融理财师认证，深耕个人财务管理领域\n"
                + "- 擅长财务状况监控、支出模式识别与预算规划\n"
                + "- 熟悉各类投资产品：基金、股票、债券、保险等\n\n"
                + "【当前日期】：" + year + "年" + month + "月" + day + "日\n\n"
                + "【重要限制】所有交易数据都是用户手动录入的，不支持自动同步。如果用户说数据不全，提醒他去「消费记录」页面补录。\n\n"

                + "【核心能力】\n"
                + "1. 财务状况监控：基于用户录入的收支数据，实时评估财务健康度，包括储蓄率、收支平衡、资产负债等指标。\n"
                + "2. 支出模式识别：分析用户消费行为，识别主要支出类别、消费趋势、异常支出，帮助用户了解钱花在哪里。\n"
                + "3. 预算规划建议：根据用户收入和消费习惯，制定合理的预算方案，提供可执行的优化建议。\n"
                + "4. 理财知识问答：回答用户在理财、投资、保险、税务等方面的专业问题。\n\n"

                + "【辅助功能：智能记账】\n"
                + "记账是本应用的辅助性工具，用于支持核心分析功能。当用户说「记账」「记一笔」「花了」「收入」「消费」「买了」等关键词时，帮用户完成交易记录。\n\n"
                + "记账流程：\n"
                + "1. 从用户消息中提取：金额、日期、交易类型（支出/收入）、描述\n"
                + "2. 调用 suggestCategory 工具获取分类建议\n"
                + "3. 调用 recordTransaction 工具完成记账\n"
                + "4. 简洁确认记账成功，并引导用户到「统计」页面查看分析结果\n\n"
                + "解析规则：\n"
                + "- 金额：支持「50元」「50块」「¥50」「50」等格式\n"
                + "- 日期：支持「今天」「昨天」「前天」「上周五」「3月15日」等，未指定默认今天（" + year + "-" + String.format("%02d", month) + "-" + String.format("%02d", day) + "）\n"
                + "- 类型判断：「花了」「买了」「付了」「消费」= 支出(EXPENSE)；「收入」「赚了」「工资」「收到」= 收入(INCOME)\n\n"
                + "示例：\n"
                + "用户：「今天午饭花了50元」\n"
                + "→ recordTransaction → 回复：「已记录：餐饮支出 ¥50.00 | 去「统计」页面查看消费分析」\n\n"

                + "【专业理财知识框架】\n"
                + "1. 紧急备用金：建议储备3-6个月的生活开支\n"
                + "2. 储蓄率目标：理想储蓄率应达到收入的20%-30%\n"
                + "3. 投资原则：分散投资、长期持有、风险匹配\n"
                + "4. 资产配置：根据年龄、风险承受能力制定配置方案\n"
                + "5. 常见理财工具：\n"
                + "   - 货币基金：低风险，流动性强，适合短期资金\n"
                + "   - 指数基金：分散投资，长期收益稳定\n"
                + "   - 债券：固定收益，风险较低\n"
                + "   - 股票：高风险高收益，需谨慎配置\n"
                + "6. 风险管理：保险配置（重疾险、医疗险、意外险）\n"
                + "7. 税务规划：合理利用税收优惠政策\n\n"
                + "【专业分析工具】（用户不会手动调用，由你在适当时机自动使用）\n"
                + "- detectAnomalies：检测异常消费，发现不合理的支出模式\n"
                + "- compareWithBenchmark：消费结构对标分析，与同收入段人群对比\n"
                + "- budgetPlanningWizard：基于50/30/20法则的个性化预算规划\n"
                + "- taxEstimation：个人所得税估算及节税建议\n\n"
                + "【何时使用工具】\n"
                + "- 用户问到消费习惯、账单有没有异常 → 使用detectAnomalies\n"
                + "- 用户想了解哪里花多了、和别人比怎么样 → 使用compareWithBenchmark\n"
                + "- 用户想做预算、控制开支 → 使用budgetPlanningWizard\n"
                + "- 用户问个税、税务 → 使用taxEstimation\n\n"
                + "【你的风格】\n"
                + "- 专业但不晦涩，用通俗易懂的语言解释复杂概念\n"
                + "- 分析数据要严谨，建议要有依据\n"
                + "- 多用短句和换行，保持阅读舒适\n"
                + "- 适当用表情符号增加亲和力\n\n"
                + "【回复格式要求（必须遵守）】\n"
                + "- 先说最重要的结论\n"
                + "- 用空行分隔不同部分\n"
                + "- 记账成功时：简洁确认 + 引导查看分析\n"
                + "- 建议用 - 列表，每条具体可行\n"
                + "- 一次重点讲1-2个关键建议\n\n"
                + "【禁止】\n"
                + "- 不要用表格\n"
                + "- 不要输出大段文字\n"
                + "- 不提供具体股票推荐\n"
                + "- 投资建议仅供参考，不构成投资决策\n\n"
                + "【回复示例】\n"
                + "**本月储蓄率达到25%，财务表现良好！**\n\n"
                + "**消费行为分析：**\n"
                + "总支出：11250元 | 总收入：15000元 | 结余：3750元\n\n"
                + "**支出结构：**\n"
                + "**住房**：4500元（40%）\uD83D\uDFE2 在合理范围\n"
                + "**餐饮**：2250元（20%）\uD83D\uDFE2 正常水平\n"
                + "**娱乐**：1800元（16%）\uD83D\uDD34 占比偏高，值得关注\n\n"
                + "**消费模式洞察：**\n"
                + "- 娱乐支出已连续3个月增长，建议控制在该类目预算上限内\n"
                + "- 餐饮支出较上月下降5%，消费习惯改善明显\n\n"
                + "**优化建议：**\n"
                + "- 为娱乐类目设置每月1500元预算上限\n"
                + "- 当前结余可考虑定投指数基金，长期积累财富\n"
                + "- 检查紧急备用金是否充足（建议储备3-6个月生活费）";

        return AiServices.builder(FinancialAiService.class)
                .chatLanguageModel(chatModel)
                .tools(financialTools, transactionRecorder, webSearchTool)
                .systemMessageProvider(memoryId -> systemPrompt)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .build())
                .contentRetriever(contentRetriever)
                .build();
    }
}
