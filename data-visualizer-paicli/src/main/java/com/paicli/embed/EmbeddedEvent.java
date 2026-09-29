package com.paicli.embed;

/** Events emitted while an embedded PaiCLI turn is running. */
public record EmbeddedEvent(Kind kind, String content) {
    public enum Kind {
        CONTENT_DELTA,
        TOOL_CALL,
        STATUS
    }
}
