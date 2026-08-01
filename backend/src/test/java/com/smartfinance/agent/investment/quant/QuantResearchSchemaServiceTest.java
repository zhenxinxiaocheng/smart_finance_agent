package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuantResearchSchemaServiceTest {

    @Test
    void usesTheAnalysisRuntimeManifestAsTheOnlyResearchParameterSource() {
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        when(client.quantRuntimeManifest()).thenReturn(manifest());
        QuantResearchSchemaService service = new QuantResearchSchemaService(client);

        QuantResearchSchemaService.SchemaSnapshot snapshot = service.load();

        assertThat(snapshot.quantConfigVersion()).isEqualTo("quant-research-v9");
        assertThat(snapshot.modelFamilies())
                .extracting(item -> item.get("code"))
                .containsExactly("A_SHARE_STOCK");
        assertThat(snapshot.parameterSchema())
                .containsEntry("schemaVersion", "research-ui-v9")
                .containsEntry("algorithms", List.of("XGBOOST"))
                .containsEntry("modelFamilies", snapshot.modelFamilies());
        assertThat(snapshot.normalizeFamily("a_share_stock")).isEqualTo("A_SHARE_STOCK");
        assertThat(snapshot.normalizeAlgorithm("xgboost")).isEqualTo("XGBOOST");
        snapshot.validateParameters(Map.of("maximumDepth", 5), "XGBOOST");
        assertThatThrownBy(() -> snapshot.validateParameters(
                Map.of("maximumDepth", 5), "REGIME_ENSEMBLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumDepth");
        assertThatThrownBy(() -> snapshot.validateParameters(
                Map.of("maximumDepth", 7), "XGBOOST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumDepth");
    }

    private static Map<String, Object> manifest() {
        return Map.of(
                "quantConfigVersion", "quant-research-v9",
                "researchSchema", Map.of(
                        "schemaVersion", "research-ui-v9",
                        "modelFamilies", List.of(Map.of(
                                "code", "A_SHARE_STOCK",
                                "name", "A股股票",
                                "predictionHeads", List.of("RELATIVE_ALPHA")
                        )),
                        "algorithms", List.of("XGBOOST"),
                        "fields", List.of(Map.of(
                                "key", "maximumDepth",
                                "label", "树最大深度",
                                "type", "INTEGER",
                                "defaultValue", 3,
                                "minimum", 1,
                                "maximum", 6,
                                "step", 1,
                                "sampling", "LINEAR",
                                "algorithms", List.of("XGBOOST")
                        )),
                        "immutableValidation", Map.of(
                                "minimumWalkForwardFolds", 5,
                                "embargoHorizonMultiplier", 1.0,
                                "maximumDmPValue", 0.05,
                                "minimumDeflatedSharpeProbability", 0.95,
                                "maximumPbo", 0.2
                        )
                )
        );
    }
}
