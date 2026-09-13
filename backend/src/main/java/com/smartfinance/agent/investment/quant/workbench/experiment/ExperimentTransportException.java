package com.smartfinance.agent.investment.quant.workbench.experiment;

public final class ExperimentTransportException extends RuntimeException {
    public ExperimentTransportException(Throwable cause) {
        super("ANALYSIS_SERVICE_UNAVAILABLE", cause);
    }
}
