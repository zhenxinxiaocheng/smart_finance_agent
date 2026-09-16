package com.smartfinance.agent.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DASHSCOPE_API_KEY is empty; embedding model will use local fallback");
            return new FallbackEmbeddingModel();
        }
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
        return knowledgeDocuments(new PathMatchingResourcePatternResolver());
    }

    List<Document> knowledgeDocuments(ResourcePatternResolver resolver) {
        try {
            Resource[] resources = resolver.getResources("classpath*:knowledge/*.txt");
            if (resources.length == 0) {
                log.warn("知识库目录不存在，跳过RAG文档加载");
                return List.of();
            }
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename));
            TextDocumentParser parser = new TextDocumentParser(StandardCharsets.UTF_8);
            List<Document> documents = new ArrayList<>(resources.length);
            for (Resource resource : resources) {
                try (var input = resource.getInputStream()) {
                    Document document = parser.parse(input);
                    document.metadata().put(Document.FILE_NAME, resource.getFilename());
                    documents.add(document);
                }
            }
            log.info("加载了 {} 个知识文档", documents.size());
            return documents;
        } catch (IOException e) {
            log.error("加载知识文档失败", e);
            return List.of();
        }
    }

    @Bean
    public ApplicationRunner ragKnowledgeInitializer(EmbeddingStoreIngestor ingestor,
                                                     List<Document> knowledgeDocuments) {
        return args -> {
            if (knowledgeDocuments.isEmpty()) {
                log.warn("RAG知识库为空，跳过索引初始化");
                return;
            }
            try {
                ingestor.ingest(knowledgeDocuments);
                log.info("RAG知识库初始化完成，已索引 {} 个文档", knowledgeDocuments.size());
            } catch (Exception e) {
                log.warn("RAG initialization failed; skip retrieval and keep chat available: {}", e.getMessage());
            }
        };
    }
}
