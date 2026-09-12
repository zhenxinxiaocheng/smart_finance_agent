package com.smartfinance.agent.investment.quant.workbench.experiment;

import java.util.*;

/** Original engine codes are retained; this is categorization, not a second qualification system. */
public final class EvidenceReasonClassifier {
    public enum Category { CONTROL_INTEGRITY, DATA_INCONSISTENCY, DATA_QUALITY, RUN_COVERAGE, SOURCE_COMPLETENESS, STRATEGY_OUTCOME_ONLY, OTHER }
    public Category classify(String code) {
        return switch(code) {
            case "CONTROL_VARIABLE_VIOLATION", "EXPERIMENT_ENVIRONMENT_CHANGED", "EXPERIMENT_TASK_LINK_INVALID",
                    "EXPERIMENT_INVARIANT_MISMATCH" -> Category.CONTROL_INTEGRITY;
            case "EXPERIMENT_DATA_INCONSISTENCY", "SNAPSHOT_CORRUPTED", "SNAPSHOT_FORMAT_UNSUPPORTED", "SNAPSHOT_NOT_FOUND" -> Category.DATA_INCONSISTENCY;
            case "DRAWDOWN_LIMIT_EXCEEDED", "MODEL_FINAL_HOLDOUT_UNQUALIFIED" -> Category.STRATEGY_OUTCOME_ONLY;
            case "CORPORATE_ACTIONS_NOT_VERIFIED", "CORPORATE_ACTION_UNSUPPORTED", "DUPLICATE_ASSET", "DUPLICATE_DATE",
                    "EMPTY_UNIVERSE", "MIXED_ASSET_CLASSES", "FACTOR_DATA_UNAVAILABLE", "RAW_PRICE_REQUIRED" -> Category.DATA_QUALITY;
            case "INSUFFICIENT_EVALUATION_DATES", "NO_EXECUTED_TRADES", "INSUFFICIENT_DATA" -> Category.RUN_COVERAGE;
            case "MODEL_NOT_FOUND", "MODEL_CONFIG_MISMATCH", "MODEL_LOOKAHEAD", "INVALID_MODEL_REF" -> Category.SOURCE_COMPLETENESS;
            default -> Category.OTHER;
        };
    }
}
