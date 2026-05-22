package com.smartfinance.agent.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.FinishReason;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class BatchEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private static final int BATCH_SIZE = 10;

    public BatchEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        if (textSegments.size() <= BATCH_SIZE) {
            return delegate.embedAll(textSegments);
        }

        List<Embedding> allEmbeddings = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        List<List<TextSegment>> batches = new ArrayList<>();
        for (int i = 0; i < textSegments.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, textSegments.size());
            batches.add(textSegments.subList(i, end));
        }

        log.info("Embedding任务: {}条文本分为{}批处理", textSegments.size(), batches.size());

        for (List<TextSegment> batch : batches) {
            try {
                Response<List<Embedding>> response = delegate.embedAll(batch);
                allEmbeddings.addAll(response.content());
            } catch (Exception e) {
                log.warn("Embedding批处理失败: {}", e.getMessage());
                errors.add(e);
            }
        }

        if (!errors.isEmpty() && allEmbeddings.isEmpty()) {
            throw new RuntimeException("Embedding全部失败", errors.get(0));
        }

        return Response.from(allEmbeddings);
    }

    @Override
    public int dimension() {
        if (delegate instanceof dev.langchain4j.model.embedding.DimensionAwareEmbeddingModel) {
            return ((dev.langchain4j.model.embedding.DimensionAwareEmbeddingModel) delegate).dimension();
        }
        return 1536;
    }
}
