package top.lbwxxc.ai.test.paicli;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import top.lbwxxc.ai.api.dto.ChatRequestDTO;
import top.lbwxxc.ai.domain.agent.service.IChatService;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalService;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamResponseDTO;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowEvent;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowResult;
import top.lbwxxc.ai.trigger.http.AgentServiceController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class PaiCliControllerContractTest {
    private static final String GRAPH = "{\"type\":\"drawio_graph\",\"nodes\":[{\"id\":\"n1\",\"label\":\"Start\",\"kind\":\"start\"}],\"edges\":[]}";

    @Test
    void synchronousAndStreamedPathsConvertTheSameFinalGraph() throws Exception {
        IChatService chat = mock(IChatService.class);
        RecordingBridge bridge = new RecordingBridge();
        AgentServiceController controller = new AgentServiceController();
        ReflectionTestUtils.setField(controller, "chatService", chat);
        ReflectionTestUtils.setField(controller, "agentStreamBridge", bridge);
        ReflectionTestUtils.setField(controller, "commandApprovalService", mock(CommandApprovalService.class));
        ReflectionTestUtils.setField(controller, "shellExecutor", mock(ShellExecutor.class));
        ChatRequestDTO request = new ChatRequestDTO();
        request.setAgentId("agent");
        request.setUserId("user");
        request.setSessionId("session");
        request.setMessage("draw");
        PaiCliWorkflowResult result = new PaiCliWorkflowResult(GRAPH, Map.of("final_result", GRAPH));
        when(chat.handleMessage("agent", "user", "session", "draw")).thenReturn(result);
        when(chat.handleMessageStream(eq("agent"), eq("user"), eq("session"),
                any(CommandExecutionContext.class), eq("draw"), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<PaiCliWorkflowEvent> listener = invocation.getArgument(5);
            listener.accept(new PaiCliWorkflowEvent(PaiCliWorkflowEvent.Kind.STAGE_STARTED, "analyst", ""));
            listener.accept(new PaiCliWorkflowEvent(PaiCliWorkflowEvent.Kind.STAGE_COMPLETED, "analyst", "intermediate"));
            return result;
        });

        String synchronousXml = controller.chat(request).getData().getContent();
        controller.chatStream(request);
        assertTrue(bridge.done.await(3, TimeUnit.SECONDS));

        List<AgentStreamResponseDTO> terminal = bridge.events.stream()
                .filter(event -> "result".equals(event.getType()) || "done".equals(event.getType())).toList();
        assertEquals(List.of("result", "done"), terminal.stream().map(AgentStreamResponseDTO::getType).toList());
        assertEquals("drawio", terminal.get(0).getStage());
        assertEquals(synchronousXml, terminal.get(0).getContent());
        assertEquals(terminal.get(0).getRequestId(), terminal.get(1).getRequestId());
    }

    @Test
    void cleanupCancelsOnlyTheMatchingRequestOnce() {
        IChatService chat = mock(IChatService.class);
        ShellExecutor shell = mock(ShellExecutor.class);
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        RecordingBridge bridge = new RecordingBridge();
        AgentServiceController controller = new AgentServiceController();
        ReflectionTestUtils.setField(controller, "chatService", chat);
        ReflectionTestUtils.setField(controller, "shellExecutor", shell);
        ReflectionTestUtils.setField(controller, "commandApprovalService", approvals);
        ReflectionTestUtils.setField(controller, "agentStreamBridge", bridge);
        CommandExecutionContext context = new CommandExecutionContext("request-1", "agent", "user", "session");
        AtomicBoolean cleaned = new AtomicBoolean();
        @SuppressWarnings("unchecked") Future<Object> task = mock(Future.class);

        ReflectionTestUtils.invokeMethod(controller, "cleanupStream", cleaned, context, task);
        ReflectionTestUtils.invokeMethod(controller, "cleanupStream", cleaned, context, task);

        verify(approvals, times(1)).cancelByRequest("request-1");
        verify(chat, times(1)).cancel(context);
        verify(shell, times(1)).closeRequestShell("request-1");
        assertEquals(List.of("request-1"), bridge.cleared);
    }

    private static final class RecordingBridge extends AgentStreamBridge {
        private final List<AgentStreamResponseDTO> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<String> cleared = new CopyOnWriteArrayList<>();

        @Override
        public void register(String sessionId, String requestId, ResponseBodyEmitter emitter) {
        }

        @Override
        public void publish(AgentStreamResponseDTO event) {
            events.add(event);
            if ("done".equals(event.getType())) {
                done.countDown();
            }
        }

        @Override
        public void clear(String requestId) {
            cleared.add(requestId);
        }
    }
}
