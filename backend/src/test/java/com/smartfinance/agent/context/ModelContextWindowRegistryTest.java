package com.smartfinance.agent.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelContextWindowRegistryTest {

    @Test
    void resolve_shouldUseKnownModelWindowBeforeFallback() {
        ModelContextWindowRegistry registry = new ModelContextWindowRegistry();

        assertThat(registry.resolve("qwen3.6-flash", 12000)).isEqualTo(131072);
        assertThat(registry.resolve("unknown-model", 12000)).isEqualTo(12000);
    }
}
