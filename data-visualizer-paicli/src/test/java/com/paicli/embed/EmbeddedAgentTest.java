package com.paicli.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddedAgentTest {

    @Test
    void usesTrustedPromptAndStartsWithoutAnyBuiltInTools() {
        FakeClient client = new FakeClient();
        EmbeddedAgent agent = new EmbeddedAgent(client, "你是绘图分析师，只回答最终结果。");

        assertTrue(agent.availableTools().isEmpty());
        assertEquals("done", agent.run("请画一个流程图").content());
        assertTrue(client.messages.get(0).content().contains("你是绘图分析师"));
        assertFalse(client.messages.get(0).content().contains("PaiCLI 是"));
        assertTrue(client.tools.isEmpty());
    }

    @Test
    void exposesOnlyExplicitlyRegisteredTool() {
        EmbeddedAgent agent = new EmbeddedAgent(new FakeClient(), "You are a diagram assistant.");
        McpToolDescriptor descriptor = new McpToolDescriptor("host", "lookup",
                "mcp__host__lookup", "Read a value", new ObjectMapper().createObjectNode());

        agent.registerMcpTool(descriptor, args -> "value");

        assertEquals(List.of("mcp__host__lookup"),
                agent.availableTools().stream().map(LlmClient.Tool::name).toList());
    }

    @Test
    void refreshesTrustedInstructionWithoutDiscardingSessionHistory() {
        FakeClient client = new FakeClient();
        EmbeddedAgent agent = new EmbeddedAgent(client, "First stage instruction");
        agent.run("first request");

        agent.setSystemInstruction("Updated stage instruction");
        agent.run("second request");

        assertEquals("Updated stage instruction", client.messages.get(0).content());
        assertEquals(2, client.messages.stream().filter(message -> "user".equals(message.role())).count());
    }

    @Test
    void emitsContentEventsAndReturnsFinalAnswer() {
        FakeClient client = new FakeClient();
        client.stream = true;
        EmbeddedAgent agent = new EmbeddedAgent(client, "Return a diagram.");
        List<EmbeddedEvent> events = new ArrayList<>();

        EmbeddedTurnResult result = agent.run("请画一个流程图", "请画一个流程图", events::add);

        assertEquals("done", result.content());
        assertEquals(List.of("do", "ne"), events.stream()
                .filter(event -> event.kind() == EmbeddedEvent.Kind.CONTENT_DELTA)
                .map(EmbeddedEvent::content).toList());
    }

    @Test
    void preservesModelFailureAsTypedException() {
        FakeClient client = new FakeClient();
        client.fail = true;
        EmbeddedAgent agent = new EmbeddedAgent(client, "Return a diagram.");

        EmbeddedTurnException error = assertThrows(EmbeddedTurnException.class,
                () -> agent.run("请画一个流程图"));

        assertEquals(EmbeddedTurnException.Kind.MODEL_IO, error.kind());
        assertInstanceOf(IOException.class, error.getCause().getCause());
    }

    @Test
    void failedToolCannotBeReportedAsSuccessfulFinalResult() {
        FakeClient client = new FakeClient();
        client.callTool = true;
        EmbeddedAgent agent = new EmbeddedAgent(client, "Return a diagram.");
        agent.registerMcpToolOutput(new McpToolDescriptor("host", "lookup", "mcp__host__lookup",
                "Lookup", new ObjectMapper().createObjectNode()), ignored -> ToolOutput.failure("rejected"));

        EmbeddedTurnException error = assertThrows(EmbeddedTurnException.class,
                () -> agent.run("draw"));

        assertEquals(EmbeddedTurnException.Kind.TOOL, error.kind());
    }

    @Test
    void cancellationReachesInFlightModelCall() throws Exception {
        FakeClient client = new FakeClient();
        client.block = true;
        EmbeddedAgent agent = new EmbeddedAgent(client, "Return a diagram.");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                agent.run("请画一个流程图");
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        worker.start();
        try {
            assertTrue(client.entered.await(2, TimeUnit.SECONDS));
            agent.cancel();
            worker.join(2_000);
            assertFalse(worker.isAlive());
            assertEquals(EmbeddedTurnException.Kind.CANCELLED,
                    assertInstanceOf(EmbeddedTurnException.class, failure.get()).kind());
            assertTrue(client.cancelCalled);
        } finally {
            client.release.countDown();
            worker.join(2_000);
        }
    }

    private static final class FakeClient implements LlmClient {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private List<Message> messages;
        private List<Tool> tools;
        private boolean stream;
        private boolean fail;
        private boolean block;
        private boolean callTool;
        private volatile boolean cancelCalled;

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            this.messages = List.copyOf(messages);
            this.tools = tools == null ? List.of() : List.copyOf(tools);
            entered.countDown();
            if (block) {
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("wait timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", error);
                }
            }
            if (fail || cancelCalled) {
                throw new IOException("model unavailable");
            }
            if (stream) {
                listener.onContentDelta("do");
                listener.onContentDelta("ne");
            }
            if (callTool) {
                return new ChatResponse("assistant", "", List.of(new ToolCall("call-1",
                        new ToolCall.Function("mcp__host__lookup", "{}"))), 1, 1);
            }
            return new ChatResponse("assistant", "done", List.of(), 1, 1);
        }

        @Override
        public String getModelName() {
            return "fake";
        }

        @Override
        public String getProviderName() {
            return "fake";
        }

        @Override
        public void cancelInFlightCalls() {
            cancelCalled = true;
            release.countDown();
        }
    }
}
