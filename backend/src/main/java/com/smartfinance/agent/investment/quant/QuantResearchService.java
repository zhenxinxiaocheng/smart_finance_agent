package com.smartfinance.agent.investment.quant;

import java.util.List;
import java.util.Map;

public interface QuantResearchService {
    List<Map<String, Object>> modelFamilies();

    Map<String, Object> parameterSchema();

    List<Map<String, Object>> benchmarks();

    List<Map<String, Object>> researchUniverses();

    Map<String, Object> createExperiment(
            Long userId,
            QuantResearchController.ExperimentRequest request
    );

    List<Map<String, Object>> experiments(Long userId);

    Map<String, Object> experiment(Long userId, Long experimentId);

    Map<String, Object> cancelExperiment(Long userId, Long experimentId);

    Map<String, Object> promoteExperiment(Long userId, Long experimentId);

    Map<String, Object> dataQuality(Long userId);

    Map<String, Object> paperStrategy(Long userId, Long strategyId);
}
