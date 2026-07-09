package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.service.RagKnowledgeService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagKnowledgeServiceImpl implements RagKnowledgeService {

    private final ContentRetriever contentRetriever;

    public RagKnowledgeServiceImpl(ContentRetriever contentRetriever) {
        this.contentRetriever = contentRetriever;
    }

    @Override
    public String retrieveRelevantContext(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            List<Content> contents = contentRetriever.retrieve(Query.from(query));
            if (contents == null || contents.isEmpty()) {
                return "";
            }
            return contents.stream()
                    .map(this::text)
                    .filter(text -> text != null && !text.isBlank())
                    .distinct()
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.warn("RAG retrieval failed: {}", e.getMessage());
            return "";
        }
    }

    private String text(Content content) {
        if (content == null || content.textSegment() == null) {
            return "";
        }
        return Objects.toString(content.textSegment().text(), "").trim();
    }
}
