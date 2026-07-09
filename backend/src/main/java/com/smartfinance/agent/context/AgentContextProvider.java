package com.smartfinance.agent.context;

import java.util.List;

public interface AgentContextProvider {

    List<ContextBlock> provide(ContextRequest request);
}
