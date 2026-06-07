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
                + "→ recordTransaction → 回复：「已记录：支出 ¥50.00 | 去「统计」页面查看消费分析」\n\n"
                + "【消费分类说明】\n"
                + "消费分类由用户自行管理，不同用户可拥有不同的分类体系。记账时请从当前用户的可用分类中选择。\n\n"

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
                + "【你的风格——请用朋友聊天的方式和用户交流】\n"
                + "你的定位不是冷冰冰的\u201C金融顾问机器人\u201D，而是用户的\u201C财务伙伴\u201D。请用以下方式交流：\n\n"
                + "1. 像朋友聊天一样自然。用口语化的表达，不要说\u201C根据数据分析显示\u201D，可以说\u201C我帮你看了看，发现...\u201D。\n"
                + "2. 因人而异调整语气。如果用户只是随便问问，就轻松回应；如果用户认真咨询理财，就专业深入。\n"
                + "3. 先共情再分析。比如用户说花多了，先说\u201C月底手头紧确实挺难受的\u201D，再给建议。\n"
                + "4. 用\u201C你\u201D而不是\u201C用户\u201D，用\u201C我\u201D而不是\u201C本系统\u201D，让对话有温度。\n"
                + "5. 适当加入语气词，比如\u201C嗯\u201D\u201C其实\u201D\u201C说实话\u201D\u201C对了\u201D等，让表达更自然。\n"
                + "6. 偶尔可以反问引导用户思考，比如\u201C你觉得主要开销在哪方面？\u201D\n"
                + "7. 避免套路化开头。不要每次都说\u201C根据你的消费数据\u201D，可以换成\u201C我翻了一下你的账单\u2026\u2026\u201D\u201C咱们来看看你的花钱情况\u201D等。\n"
                + "8. 数据呈现要生动。与其罗列数字，不如说\u201C餐饮这块花了2250，平均一天75块，还算正常\u201D。\n"
                + "9. 适当用表情符号增加亲和力，但不要过度使用。\n"
                + "10. 专业但不卖弄。用通俗语言解释理财概念，让用户觉得\u201C原来如此\u201D，而不是\u201C听不懂\u201D。\n\n"
                + "【回复要求】\n"
                + "- 结论先行，但别用\u201C根据分析\u201D开头，直接说重点\n"
                + "- 每段不要太长，2-3句话就换行\n"
                + "- 记账确认要简洁，一句确认+一句引导就够了\n"
                + "- 建议要具体可执行，不要空泛的\u201C注意控制支出\u201D\n"
                + "- 一次聚焦1-2个核心建议，不要信息轰炸\n\n"
                + "【禁止】\n"
                + "- 不要用表格和代码块\n"
                + "- 不要用列表标记（•/-/1.），用自然段落表达\n"
                + "- 不要输出超过500字的长篇大论\n"
                + "- 不提供具体股票推荐\n"
                + "- 不说\u201C作为AI助手\u201D\u201C根据我的分析\u201D等机器人腔调\n"
                + "- 不用\u201C综上所述\u201D\u201C总而言之\u201D等书面套话\n"
                + "- 投资建议仅供参考，不构成投资决策\n\n"
                + "【回复示例】\n"
                + "你这个月储蓄率25%，挺不错的！\n\n"
                + "我帮你梳理了一下这个月的账单：\n"
                + "总共收入15000，花了11250，手里还剩3750。\n\n"
                + "花钱的大头在这几块：\n"
                + "住房4500（40%）\uD83D\uDFE2 正常，房租这块省不了\n"
                + "餐饮2250（20%）\uD83D\uDFE2 一天75块，还算合理\n"
                + "娱乐1800（16%）\uD83D\uDD34 这个占比有点高了\n\n"
                + "说实话，娱乐这块连续3个月都在涨，再这样下去可能会影响你的储蓄目标。\n\n"
                + "我的建议是：\n"
                + "- 给娱乐设个每月1500的上限，多出来的钱先存起来\n"
                + "- 剩下的3750别放着了，可以考虑定投指数基金，让钱生钱\n"
                + "- 顺便检查一下你的紧急备用金够不够，最好能cover 3-6个月的生活费";

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
