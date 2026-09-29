package top.lbwxxc.ai.domain.agent.service.paicli;

/** Stable domain failures for the PaiCLI workflow boundary. */
public final class PaiCliWorkflowException extends RuntimeException {
    public enum Reason {
        CONFIG_INVALID,
        NOT_CONFIGURED,
        AGENT_NOT_FOUND,
        SESSION_NOT_FOUND,
        SESSION_MISMATCH,
        SESSION_EXPIRED,
        WORKFLOW_STATE
    }

    private final Reason reason;

    public PaiCliWorkflowException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
