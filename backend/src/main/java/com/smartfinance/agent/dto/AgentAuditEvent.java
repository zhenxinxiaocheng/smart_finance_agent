package com.smartfinance.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class AgentAuditEvent {

    private String id;

    private String source;

    private String title;

    private String summary;

    private String status;

    private String rawStatus;

    private String severity;

    private LocalDateTime time;

    private String traceId;

    private AgentAuditTarget target;

    @Data
    public static class AgentAuditTarget {

        private String path;

        private Map<String, Object> query;
    }
}
