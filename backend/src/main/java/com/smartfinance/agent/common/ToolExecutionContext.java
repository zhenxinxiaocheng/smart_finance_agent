package com.smartfinance.agent.common;

/** Pending action created by the current tool invocation. */
public final class ToolExecutionContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private static final class State { Long pendingActionId; }
    private ToolExecutionContext() {}
    public static void begin() { CURRENT.set(new State()); }
    public static void pending(Long id) { if (CURRENT.get() != null) CURRENT.get().pendingActionId = id; }
    public static Long pendingActionId() { return CURRENT.get() == null ? null : CURRENT.get().pendingActionId; }
    public static void clear() { CURRENT.remove(); }
}
