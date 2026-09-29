package com.paicli.embed;

/** The final assistant answer, separate from intermediate stream events. */
public record EmbeddedTurnResult(String content) {
}
