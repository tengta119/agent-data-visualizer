package com.paicli.agent;

/** A typed failure for hosts embedding the PaiCLI ReAct loop. */
public final class AgentRunException extends RuntimeException {
    public enum Reason {
        MODEL_IO,
        CANCELLED
    }

    private final Reason reason;

    public AgentRunException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentRunException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
