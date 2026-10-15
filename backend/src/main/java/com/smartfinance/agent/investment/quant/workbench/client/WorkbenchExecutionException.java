package com.smartfinance.agent.investment.quant.workbench.client;

public final class WorkbenchExecutionException extends RuntimeException {
    private final String code;

    public WorkbenchExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
