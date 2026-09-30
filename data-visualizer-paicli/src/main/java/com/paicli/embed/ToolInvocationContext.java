package com.paicli.embed;

import java.util.Objects;
import java.util.concurrent.Callable;

/** Carries an immutable host request identity across PaiCLI's parallel tool workers. */
public final class ToolInvocationContext {
    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

    private ToolInvocationContext() {
    }

    public static Object current() {
        return CURRENT.get();
    }

    public static <T> T call(Object context, Callable<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        Object previous = CURRENT.get();
        try {
            if (context == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(context);
            }
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
