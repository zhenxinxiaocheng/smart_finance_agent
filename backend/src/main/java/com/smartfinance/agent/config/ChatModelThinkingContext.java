package com.smartfinance.agent.config;

/** Scoped mode for the model calls made while handling one chat request. */
public final class ChatModelThinkingContext {
    private static final ThreadLocal<Boolean> OVERRIDE = new ThreadLocal<>();

    private ChatModelThinkingContext() {}

    public static Boolean current() {
        return OVERRIDE.get();
    }

    public static Scope override(Boolean enabled) {
        Boolean previous = OVERRIDE.get();
        if (enabled == null) {
            OVERRIDE.remove();
        } else {
            OVERRIDE.set(enabled);
        }
        return () -> {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
