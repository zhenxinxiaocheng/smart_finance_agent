package com.smartfinance.agent.context;

public interface ContextCompressor {

    boolean supports(ContextBlock block);

    ContextBlock compress(ContextBlock block, ContextRequest request, int targetTokens);
}
