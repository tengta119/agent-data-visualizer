package com.paicli.runtime;

public final class CancellationContext {
    // A process-wide fallback would let one HTTP request cancel another request.
    // Hosts must bind the token explicitly on any worker thread they create.
    private static final ThreadLocal<CancellationToken> LOCAL = new ThreadLocal<>();

    private CancellationContext() {
    }

    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        LOCAL.set(token);
        return token;
    }

    /** Binds an independently owned token to the current embedded execution thread. */
    public static Scope bind(CancellationToken token) {
        CancellationToken previous = LOCAL.get();
        LOCAL.set(token);
        return () -> {
            if (previous == null) {
                LOCAL.remove();
            } else {
                LOCAL.set(previous);
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static CancellationToken current() {
        return LOCAL.get();
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    public static void clear(CancellationToken token) {
        if (LOCAL.get() == token) {
            LOCAL.remove();
        }
    }
}
