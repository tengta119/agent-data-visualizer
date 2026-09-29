package com.paicli.embed;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/** Routes Agent activity to the host without reading or writing a terminal. */
final class EmbeddedRenderer implements Renderer {
    private final PrintStream discardedOutput = new PrintStream(OutputStream.nullOutputStream(), true,
            StandardCharsets.UTF_8);
    private final Consumer<EmbeddedEvent> listener;

    EmbeddedRenderer(Consumer<EmbeddedEvent> listener) {
        this.listener = listener == null ? event -> { } : listener;
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
        discardedOutput.close();
    }

    @Override
    public PrintStream stream() {
        return discardedOutput;
    }

    @Override
    public boolean rendersReasoning() {
        return false;
    }

    @Override
    public void appendAssistantContentDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
            listener.accept(new EmbeddedEvent(EmbeddedEvent.Kind.CONTENT_DELTA, delta));
        }
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null) {
            return;
        }
        for (LlmClient.ToolCall call : toolCalls) {
            listener.accept(new EmbeddedEvent(EmbeddedEvent.Kind.TOOL_CALL, call.function().name()));
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (status != null) {
            listener.accept(new EmbeddedEvent(EmbeddedEvent.Kind.STATUS, status.phase()));
        }
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        return ApprovalResult.reject("Embedded host has no terminal approval channel");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        return -1;
    }
}
