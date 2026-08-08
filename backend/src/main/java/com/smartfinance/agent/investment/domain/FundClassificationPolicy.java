package com.smartfinance.agent.investment.domain;

import java.util.Objects;

public final class FundClassificationPolicy {

    private FundClassificationPolicy() {
    }

    public static boolean shouldReplace(
            String currentCategory,
            String currentSource,
            String currentVersion,
            String incomingCategory,
            String incomingSource,
            String incomingVersion) {
        if (!known(incomingCategory) || blank(incomingSource) || blank(incomingVersion)) {
            return false;
        }
        if (!known(currentCategory)) {
            return true;
        }
        return Objects.equals(currentSource, incomingSource)
                && !Objects.equals(currentVersion, incomingVersion);
    }

    public static boolean known(String category) {
        return !blank(category) && !"UNKNOWN".equals(category);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
