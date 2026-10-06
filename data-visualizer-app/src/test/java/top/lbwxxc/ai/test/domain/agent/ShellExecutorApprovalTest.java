package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.adapter.port.IBusinessPort;
import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContextHolder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalDecision;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalService;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalState;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.PendingApprovalStore;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.PendingCommandApproval;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandAuditRecorder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandExecutionPolicyProperties;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReviewer;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellRegistry;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.ProcessLocalShellLauncher;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShellExecutor 交互审批行为测试：等待期间不执行、批准后执行、拒绝/过期/取消/无上下文不执行、
 * clients 特殊查询不绕过审查边界、请求间审批隔离。
 */
class ShellExecutorApprovalTest {

    private final ExecutorService workerPool = Executors.newCachedThreadPool();
    private final AtomicInteger actionCalls = new AtomicInteger();
    private final AtomicInteger clientsCalls = new AtomicInteger();

    private final RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
    private final CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
    private final PendingApprovalStore store = new PendingApprovalStore();
    private final CommandApprovalService approvalService =
            new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

    private ShellExecutor executor;

    @AfterEach
    void tearDown() {
        CommandExecutionContextHolder.clear();
        workerPool.shutdownNow();
    }

    private static class RecordingAgentStreamBridge extends AgentStreamBridge {
        final List<String> required = new CopyOnWriteArrayList<>();

        @Override
        public boolean contains(String requestId) {
            return true;
        }

        @Override
        public void publishApprovalRequired(String requestId, String approvalId, String commandType,
                                            String hostName, String command, String reason, Long expiresAt) {
            required.add(requestId + ":" + approvalId);
        }
    }

    private IBusinessPort countingBusinessPort() {
        return new IBusinessPort() {
            @Override
            public GatewayResponseVO action(GatewayCommandEntity commandEntity) {
                actionCalls.incrementAndGet();
                return GatewayResponseVO.builder().status("success").message("ok").build();
            }

            @Override
            public GatewayResponseVO queryClients() {
                clientsCalls.incrementAndGet();
                return GatewayResponseVO.builder().status("success").message("client-list").build();
            }
        };
    }

    private ShellExecutor newExecutor() {
        properties.setRemoteAllowedHosts(List.of("client-a"));
        executor = new ShellExecutor(
                countingBusinessPort(),
                new CommandPolicyReviewer(properties),
                new CommandAuditRecorder(),
                properties,
                approvalService,
                new ProcessLocalShellLauncher(),
                new LocalShellRegistry(bridge)
        );
        return executor;
    }

    private CommandExecutionContext context(String requestId) {
        return new CommandExecutionContext(requestId, "agent-1", "user-1", "sess-" + requestId);
    }

    private ShellExecutor.CommandRequest remotePush(String host) {
        return new ShellExecutor.CommandRequest("git push origin main", ShellExecutor.CommandTypeEnum.remote, host);
    }

    private Future<ShellExecutor.CommandResponse> executeWithContext(String requestId, ShellExecutor.CommandRequest request) {
        CommandExecutionContext context = context(requestId);
        return workerPool.submit(() -> {
            try {
                CommandExecutionContextHolder.set(context);
                return executor.execute(request);
            } finally {
                CommandExecutionContextHolder.clear();
            }
        });
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("条件在 " + timeoutMillis + "ms 内未满足");
    }

    @Test
    void wildcardCommandWaitsForOneTimeApprovalBeforeRemoteExecution() throws Exception {
        newExecutor();
        properties.setRemoteAllow(List.of());
        properties.setRemotePrompt(List.of("*"));
        properties.setRemoteAllowedHosts(List.of("*"));
        Future<ShellExecutor.CommandResponse> waiting = executeWithContext("req-all",
                new ShellExecutor.CommandRequest("systeminfo | findstr CPU", ShellExecutor.CommandTypeEnum.remote, "client-new"));
        awaitUntil(() -> !store.findByRequestId("req-all").isEmpty(), 2000);
        PendingCommandApproval approval = store.findByRequestId("req-all").get(0);
        assertEquals(0, actionCalls.get());
        assertFalse(waiting.isDone());
        approvalService.resolve("req-all", approval.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);
        assertEquals("success", waiting.get(3, TimeUnit.SECONDS).getResponseStatus());
        assertEquals(1, actionCalls.get());
    }

    @Test
    void allowCommandExecutesWithoutApproval() {
        newExecutor();
        ShellExecutor.CommandResponse response = executor.execute(
                new ShellExecutor.CommandRequest("pwd", ShellExecutor.CommandTypeEnum.remote, "client-a"));

        assertEquals("success", response.getResponseStatus());
        assertEquals(1, actionCalls.get());
        assertTrue(bridge.required.isEmpty());
    }

    @Test
    void clientsSpecialQueryDoesNotBypassPolicyAndRunsWithoutApproval() {
        newExecutor();
        ShellExecutor.CommandResponse response = executor.execute(
                new ShellExecutor.CommandRequest("clients", ShellExecutor.CommandTypeEnum.local, ""));

        assertEquals("success", response.getResponseStatus());
        assertEquals(1, clientsCalls.get());
        assertEquals(0, actionCalls.get());
        assertTrue(bridge.required.isEmpty());
    }

    @Test
    void promptCommandWithoutStreamContextFailsFastWithoutWaitingOrExecuting() {
        properties.setRemotePrompt(List.of("git push"));
        newExecutor();
        long start = System.currentTimeMillis();
        ShellExecutor.CommandResponse response = executor.execute(remotePush("client-a"));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, actionCalls.get());
        assertTrue(bridge.required.isEmpty());
        assertTrue(elapsed < 2000, "无上下文时不应进入等待，实际耗时 " + elapsed + "ms");
    }

    @Test
    void promptCommandRejectedDoesNotExecute() throws Exception {
        properties.setRemotePrompt(List.of("git push"));
        newExecutor();

        Future<ShellExecutor.CommandResponse> waiting = executeWithContext("req-1", remotePush("client-a"));
        awaitUntil(() -> !store.findByRequestId("req-1").isEmpty(), 2000);
        PendingCommandApproval approval = store.findByRequestId("req-1").get(0);

        approvalService.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.REJECT);

        ShellExecutor.CommandResponse response = waiting.get(3, TimeUnit.SECONDS);
        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, actionCalls.get());
    }

    @Test
    void promptCommandExecutesOnlyAfterApproval() throws Exception {
        properties.setRemotePrompt(List.of("git push"));
        newExecutor();

        Future<ShellExecutor.CommandResponse> waiting = executeWithContext("req-1", remotePush("client-a"));
        awaitUntil(() -> !store.findByRequestId("req-1").isEmpty(), 2000);
        PendingCommandApproval approval = store.findByRequestId("req-1").get(0);

        // 等待期间不能写入 remote
        assertEquals(0, actionCalls.get());
        approvalService.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);

        ShellExecutor.CommandResponse response = waiting.get(3, TimeUnit.SECONDS);
        assertEquals("success", response.getResponseStatus());
        assertEquals(1, actionCalls.get());
        assertEquals(CommandApprovalState.APPROVED, approval.state());
    }

    @Test
    void promptCommandExpiredDoesNotExecute() throws Exception {
        properties.setRemotePrompt(List.of("git push"));
        newExecutor();
        properties.setApprovalTimeoutMillis(100L);

        Future<ShellExecutor.CommandResponse> waiting = executeWithContext("req-1", remotePush("client-a"));
        ShellExecutor.CommandResponse response = waiting.get(3, TimeUnit.SECONDS);

        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, actionCalls.get());
    }

    @Test
    void promptCommandCanceledByStreamCleanupDoesNotExecute() throws Exception {
        properties.setRemotePrompt(List.of("git push"));
        newExecutor();

        Future<ShellExecutor.CommandResponse> waiting = executeWithContext("req-1", remotePush("client-a"));
        awaitUntil(() -> !store.findByRequestId("req-1").isEmpty(), 2000);

        // 模拟 onCompletion/onTimeout/onError 的清理路径
        approvalService.cancelByRequest("req-1");

        ShellExecutor.CommandResponse response = waiting.get(3, TimeUnit.SECONDS);
        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, actionCalls.get());
        assertTrue(store.findByRequestId("req-1").isEmpty());
    }

    @Test
    void approvalsAreIsolatedBetweenConcurrentRequests() throws Exception {
        properties.setRemotePrompt(List.of("git push"));
        newExecutor();

        Future<ShellExecutor.CommandResponse> waitingA = executeWithContext("req-a", remotePush("client-a"));
        Future<ShellExecutor.CommandResponse> waitingB = executeWithContext("req-b", remotePush("client-a"));
        awaitUntil(() -> store.findByRequestId("req-a").size() == 1 && store.findByRequestId("req-b").size() == 1, 2000);

        // 只批准 req-a：A 执行，B 保持等待且不执行
        PendingCommandApproval approvalA = store.findByRequestId("req-a").get(0);
        approvalService.resolve("req-a", approvalA.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);

        assertEquals("success", waitingA.get(3, TimeUnit.SECONDS).getResponseStatus());
        assertEquals(1, actionCalls.get());
        assertFalse(waitingB.isDone());

        approvalService.cancelByRequest("req-b");
        assertEquals("forbidden", waitingB.get(3, TimeUnit.SECONDS).getResponseStatus());
        assertEquals(1, actionCalls.get());
    }

    @Test
    void forbiddenCommandNeverCreatesApprovalEvenWhenPromptConfigured() {
        properties.setRemotePrompt(List.of("git push"));
        newExecutor();

        ShellExecutor.CommandResponse response = executor.execute(new ShellExecutor.CommandRequest(
                "git push origin main && kill -9 1", ShellExecutor.CommandTypeEnum.remote, "client-a"));

        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, actionCalls.get());
        assertTrue(bridge.required.isEmpty());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void threadLocalContextIsClearedByExecutionBoundaryFinally() throws Exception {
        newExecutor();
        CommandExecutionContext context = context("req-1");
        Future<ShellExecutor.CommandResponse> task = workerPool.submit(() -> {
            try {
                CommandExecutionContextHolder.set(context);
                assertEquals("req-1", CommandExecutionContextHolder.get().requestId());
                return executor.execute(new ShellExecutor.CommandRequest(
                        "pwd", ShellExecutor.CommandTypeEnum.remote, "client-a"));
            } finally {
                CommandExecutionContextHolder.clear();
            }
        });

        assertEquals("success", task.get(3, TimeUnit.SECONDS).getResponseStatus());
        // 执行线程结束后，后续复用不能读到上次请求的 requestId
        Future<CommandExecutionContext> leftover = workerPool.submit(CommandExecutionContextHolder::get);
        assertNull(leftover.get(3, TimeUnit.SECONDS));
    }
}
