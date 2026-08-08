package com.smartfinance.agent.investment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FundClassificationPolicyTest {

    @Test
    void knownProviderCategoryReplacesMissingClassification() {
        assertThat(FundClassificationPolicy.shouldReplace(
                null, null, null,
                "QDII_INDEX_FUND", "AKSHARE_FUND_NAME_EM", "fund-classification-v1"
        )).isTrue();
    }

    @Test
    void unknownIncomingCategoryNeverOverwritesKnownClassification() {
        assertThat(FundClassificationPolicy.shouldReplace(
                "COMMODITY_FUND", "CURATED_PROFILE", "official-2026",
                "UNKNOWN", "AKSHARE_FUND_NAME_EM", "fund-classification-v1"
        )).isFalse();
    }

    @Test
    void differentSourceDoesNotOverwriteExistingKnownClassification() {
        assertThat(FundClassificationPolicy.shouldReplace(
                "COMMODITY_FUND", "CURATED_PROFILE", "official-2026",
                "OTHER_INDEX_FUND", "AKSHARE_FUND_NAME_EM", "fund-classification-v1"
        )).isFalse();
    }

    @Test
    void sameSourceCanUpgradeItsVersion() {
        assertThat(FundClassificationPolicy.shouldReplace(
                "INDEX_FUND", "AKSHARE_FUND_NAME_EM", "fund-classification-v0",
                "INDEX_FUND", "AKSHARE_FUND_NAME_EM", "fund-classification-v1"
        )).isTrue();
    }
}
