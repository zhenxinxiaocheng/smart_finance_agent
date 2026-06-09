package com.smartfinance.agent.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class FallbackEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSION = 1536;

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        log.warn("DashScope embedding model is unavailable; returning fallback embeddings");
        List<Embedding> embeddings = textSegments.stream()
                .map(ignored -> Embedding.from(new float[DIMENSION]))
                .toList();
        return Response.from(embeddings);
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }
}
