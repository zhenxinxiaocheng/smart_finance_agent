package com.smartfinance.agent.common;

public class UserIdContext {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

    public static void set(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static Long get() {
        return USER_ID_HOLDER.get();
    }

    public static void clear() {
        USER_ID_HOLDER.remove();
    }
}
