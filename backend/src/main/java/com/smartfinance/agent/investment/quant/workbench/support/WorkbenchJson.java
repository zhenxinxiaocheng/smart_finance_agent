package com.smartfinance.agent.investment.quant.workbench.support;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WorkbenchJson {
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public WorkbenchJson(com.fasterxml.jackson.databind.ObjectMapper json) {
        this.json = json;
    }

    public String encode(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法保存配置", e);
        }
    }

    public Map<String, Object> decode(Object o) {
        try {
            return o == null
                    ? new LinkedHashMap<>()
                    : json.readValue(str(o), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("历史记录损坏", e);
        }
    }
}
