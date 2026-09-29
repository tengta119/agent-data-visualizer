package com.paicli.embed;

/** Typed failure for callers that need to distinguish cancellation from model errors. */
public final class EmbeddedTurnException extends RuntimeException {
    public enum Kind {
        MODEL_IO,
        CANCELLED,
        EXECUTION
    }

    private final Kind kind;

    public EmbeddedTurnException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
