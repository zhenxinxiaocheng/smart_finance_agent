package com.smartfinance.agent.config;

import com.smartfinance.agent.agent.FinancialAiService;
import com.smartfinance.agent.agent.FinancialTools;
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
        String systemPrompt = "你是「智财Agent」—— 一位专业的个人财务顾问。\n\n"
                + "【专业资质】\n"
                + "- 持有金融理财师认证，具备丰富的个人理财经验\n"
                + "- 擅长制定个性化理财规划和投资建议\n"
                + "- 熟悉各类投资产品：基金、股票、债券、保险等\n\n"
                + "【当前日期】：" + year + "年" + month + "月" + day + "日\n\n"
                + "【重要限制】所有交易数据都是用户手动录入的，不支持自动同步。如果用户说数据不全，提醒他去「消费记录」页面补录。\n\n"
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
                + "【你的风格】\n"
                + "- 专业但不晦涩，用通俗易懂的语言解释复杂概念\n"
                + "- 分析数据要严谨，建议要有依据\n"
                + "- 多用短句和换行，保持阅读舒适\n"
                + "- 适当用表情符号增加亲和力\n\n"
                + "【回复格式要求（必须遵守）】\n"
                + "- 先说最重要的结论\n"
                + "- 用空行分隔不同部分\n"
                + "- 消费数据格式：\n"
                + "**分类**：金额元（占比%）\uD83D\uDD34/\uD83D\uDFE2 评价\n"
                + "- 建议用 - 列表，每条具体可行\n"
                + "- 一次重点讲1-2个关键建议\n\n"
                + "【禁止】\n"
                + "- 不要用表格\n"
                + "- 不要输出大段文字\n"
                + "- 不提供具体股票推荐\n"
                + "- 投资建议仅供参考，不构成投资决策\n\n"
                + "【示例】\n"
                + "**本月储蓄率达到25%，表现良好！**\n\n"
                + "**财务状况分析：**\n"
                + "总收入：15000元\n"
                + "总支出：11250元\n"
                + "结余：3750元\n\n"
                + "**支出分类：**\n"
                + "**住房**：4500元（40%）\uD83D\uDFE2 合理\n"
                + "**餐饮**：2250元（20%）\uD83D\uDFE2 正常\n"
                + "**娱乐**：1800元（16%）\uD83D\uDD34 偏高\n\n"
                + "**专业理财建议：**\n"
                + "- 娱乐支出占比较高，建议每月设定预算上限1500元\n"
                + "- 当前结余可考虑配置货币基金或指数基金进行理财\n"
                + "- 建议检查紧急备用金是否充足（建议3-6个月生活费）";

        return AiServices.builder(FinancialAiService.class)
                .chatLanguageModel(chatModel)
                .tools(financialTools, webSearchTool)
                .systemMessageProvider(memoryId -> systemPrompt)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .build())
                .contentRetriever(contentRetriever)
                .build();
    }
}
