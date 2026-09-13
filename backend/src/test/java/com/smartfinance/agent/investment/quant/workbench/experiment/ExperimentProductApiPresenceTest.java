package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class ExperimentProductApiPresenceTest {
    @Test
    void productApiHasDedicatedControllerServiceAndDtos() {
        assertThatCode(() -> {
            Class.forName("com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentController");
            Class.forName("com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentProductService");
            Class.forName("com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentProductDtos");
        }).doesNotThrowAnyException();
    }
}
