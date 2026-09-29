package top.lbwxxc.ai.domain.agent.service.paicli;

/** Stage-scoped event; the host maps it to its HTTP stream protocol in TASK-008. */
public record PaiCliWorkflowEvent(Kind kind, String stage, String content) {
    public enum Kind {
        STAGE_STARTED,
        CONTENT_DELTA,
        TOOL_CALL,
        STATUS,
        STAGE_COMPLETED
    }
}
