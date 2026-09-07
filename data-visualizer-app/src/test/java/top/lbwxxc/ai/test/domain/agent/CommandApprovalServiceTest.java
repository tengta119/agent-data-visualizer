package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalDecision;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalResolveResult;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalResolveStatus;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalResult;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalService;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalState;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.PendingApprovalStore;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.PendingCommandApproval;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandAuditRecorder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandExecutionPolicyProperties;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyDecision;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReview;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批服务状态竞态、超时、取消、重复决定与事件路由测试。
 * 使用可控超时与 RecordingBridge，不依赖真实 HTTP/SSE。
 */
class CommandApprovalServiceTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static class RecordingAgentStreamBridge extends AgentStreamBridge {
        final List<String> required = new CopyOnWriteArrayList<>();
        final List<String> resolved = new CopyOnWriteArrayList<>();

        @Override
        public boolean contains(String requestId) {
            return true;
        }

        @Override
        public void publishApprovalRequired(String requestId, String approvalId, String commandType,
                                            String hostName, String command, String reason, Long expiresAt) {
            required.add(requestId + ":" + approvalId);
        }

        @Override
        public void publishApprovalResolved(String requestId, String approvalId, String status) {
            resolved.add(requestId + ":" + approvalId + ":" + status);
        }
    }

    private CommandPolicyReview promptReview() {
        return new CommandPolicyReview(CommandPolicyDecision.PROMPT, "命令需要用户审批后执行",
                List.of("git push origin main"));
    }

    private ShellExecutor.CommandRequest remotePushCommand() {
        return new ShellExecutor.CommandRequest("git push origin main",
                ShellExecutor.CommandTypeEnum.remote, "client-a");
    }

    private CommandExecutionContext context(String requestId) {
        return new CommandExecutionContext(requestId, "agent-1", "user-1", "sess-" + requestId);
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
    void approveOnceWakesMatchingWaiterAndSendsResolved() throws Exception {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        Future<CommandApprovalResult> waiter = executor.submit(() ->
                service.requestApproval(context("req-1"), remotePushCommand(), promptReview()));

        awaitUntil(() -> !store.findByRequestId("req-1").isEmpty(), 2000);
        PendingCommandApproval approval = store.findByRequestId("req-1").get(0);
        assertTrue(bridge.required.stream().anyMatch(item -> item.startsWith("req-1:")));
        assertEquals(CommandApprovalState.PENDING, approval.state());

        CommandApprovalResolveResult outcome = service.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);
        assertEquals(CommandApprovalResolveStatus.APPROVED, outcome.getStatus());

        CommandApprovalResult result = waiter.get(2, TimeUnit.SECONDS);
        assertTrue(result.isApproved());
        assertEquals(CommandApprovalState.APPROVED, approval.state());
        assertTrue(bridge.resolved.stream().anyMatch(item -> item.equals("req-1:" + approval.getApprovalId() + ":approved")));
    }

    @Test
    void rejectWakesWaiterAndCommandDoesNotExecute() throws Exception {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        Future<CommandApprovalResult> waiter = executor.submit(() ->
                service.requestApproval(context("req-1"), remotePushCommand(), promptReview()));

        awaitUntil(() -> !store.findByRequestId("req-1").isEmpty(), 2000);
        PendingCommandApproval approval = store.findByRequestId("req-1").get(0);

        CommandApprovalResolveResult outcome = service.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.REJECT);
        assertEquals(CommandApprovalResolveStatus.REJECTED, outcome.getStatus());

        CommandApprovalResult result = waiter.get(2, TimeUnit.SECONDS);
        assertFalse(result.isApproved());
        assertEquals(CommandApprovalState.REJECTED, approval.state());
        assertTrue(bridge.resolved.stream().anyMatch(item -> item.endsWith(":rejected")));
    }

    @Test
    void timeoutExpiresWaiterAndNeverDefaultsToApproved() throws Exception {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        properties.setApprovalTimeoutMillis(80L);
        RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        Future<CommandApprovalResult> waiter = executor.submit(() ->
                service.requestApproval(context("req-1"), remotePushCommand(), promptReview()));

        CommandApprovalResult result = waiter.get(2, TimeUnit.SECONDS);
        assertFalse(result.isApproved());
        assertEquals(CommandApprovalState.EXPIRED, result.getState());
        assertTrue(bridge.resolved.stream().anyMatch(item -> item.endsWith(":expired")));

        PendingCommandApproval approval = store.findByRequestId("req-1").get(0);
        assertEquals(CommandApprovalState.EXPIRED, approval.state());
        CommandApprovalResolveResult late = service.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);
        assertEquals(CommandApprovalResolveStatus.EXPIRED, late.getStatus());
    }

    @Test
    void cancelByRequestCancelsPendingAndCleansRecords() throws Exception {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        Future<CommandApprovalResult> waiter = executor.submit(() ->
                service.requestApproval(context("req-1"), remotePushCommand(), promptReview()));

        awaitUntil(() -> !store.findByRequestId("req-1").isEmpty(), 2000);
        PendingCommandApproval approval = store.findByRequestId("req-1").get(0);

        service.cancelByRequest("req-1");

        CommandApprovalResult result = waiter.get(2, TimeUnit.SECONDS);
        assertFalse(result.isApproved());
        assertEquals(CommandApprovalState.CANCELLED, result.getState());
        assertTrue(store.findByRequestId("req-1").isEmpty());
        assertTrue(bridge.resolved.stream().anyMatch(item -> item.endsWith(":cancelled")));

        // 已取消请求的后续决定不得生效
        CommandApprovalResolveResult late = service.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);
        assertEquals(CommandApprovalResolveStatus.NOT_FOUND, late.getStatus());
    }

    @Test
    void duplicateDecisionsCannotChangeTerminalState() throws Exception {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        Future<CommandApprovalResult> waiter = executor.submit(() ->
                service.requestApproval(context("req-1"), remotePushCommand(), promptReview()));

        awaitUntil(() -> !store.findByRequestId("req-1").isEmpty(), 2000);
        PendingCommandApproval approval = store.findByRequestId("req-1").get(0);

        assertEquals(CommandApprovalResolveStatus.APPROVED,
                service.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE).getStatus());
        // 已批准后再拒绝：状态不被改变，返回 already_resolved
        CommandApprovalResolveResult second = service.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.REJECT);
        assertEquals(CommandApprovalResolveStatus.ALREADY_RESOLVED, second.getStatus());
        assertEquals(CommandApprovalState.APPROVED, approval.state());
        assertTrue(waiter.get(2, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void wrongRequestIdDoesNotAffectOtherApprovals() throws Exception {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        Future<CommandApprovalResult> waiterA = executor.submit(() ->
                service.requestApproval(context("req-a"), remotePushCommand(), promptReview()));
        Future<CommandApprovalResult> waiterB = executor.submit(() ->
                service.requestApproval(context("req-b"), remotePushCommand(), promptReview()));

        awaitUntil(() -> store.findByRequestId("req-a").size() == 1 && store.findByRequestId("req-b").size() == 1, 2000);
        PendingCommandApproval approvalA = store.findByRequestId("req-a").get(0);
        PendingCommandApproval approvalB = store.findByRequestId("req-b").get(0);

        // 用错误 requestId 提交到 A：不影响 A
        assertEquals(CommandApprovalResolveStatus.REQUEST_MISMATCH,
                service.resolve("req-b", approvalA.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE).getStatus());
        assertEquals(CommandApprovalState.PENDING, approvalA.state());

        assertEquals(CommandApprovalResolveStatus.APPROVED,
                service.resolve("req-a", approvalA.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE).getStatus());
        assertEquals(CommandApprovalResolveStatus.REJECTED,
                service.resolve("req-b", approvalB.getApprovalId(), CommandApprovalDecision.REJECT).getStatus());

        assertTrue(waiterA.get(2, TimeUnit.SECONDS).isApproved());
        assertFalse(waiterB.get(2, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void approvalWakesOnlyItsOwnRequestFuture() throws Exception {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        Future<CommandApprovalResult> waiterA = executor.submit(() ->
                service.requestApproval(context("req-a"), remotePushCommand(), promptReview()));
        Future<CommandApprovalResult> waiterB = executor.submit(() ->
                service.requestApproval(context("req-b"), remotePushCommand(), promptReview()));

        awaitUntil(() -> store.findByRequestId("req-a").size() == 1 && store.findByRequestId("req-b").size() == 1, 2000);
        PendingCommandApproval approvalA = store.findByRequestId("req-a").get(0);

        // 只批准 A：A 被唤醒，B 仍在 PENDING
        service.resolve("req-a", approvalA.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);
        assertTrue(waiterA.get(2, TimeUnit.SECONDS).isApproved());
        assertEquals(CommandApprovalState.PENDING, store.findByRequestId("req-b").get(0).state());

        service.cancelByRequest("req-b");
        assertFalse(waiterB.get(2, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void isApprovedAndMatchingValidatesDigestAndState() throws Exception {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        Future<CommandApprovalResult> waiter = executor.submit(() ->
                service.requestApproval(context("req-1"), remotePushCommand(), promptReview()));
        awaitUntil(() -> !store.findByRequestId("req-1").isEmpty(), 2000);
        PendingCommandApproval approval = store.findByRequestId("req-1").get(0);

        // 批准前快照复核应失败
        assertFalse(service.isApprovedAndMatching(remotePushCommand(), approval.getApprovalId(), "req-1"));

        service.resolve("req-1", approval.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);
        assertTrue(waiter.get(2, TimeUnit.SECONDS).isApproved());

        // 摘要一致才通过
        assertTrue(service.isApprovedAndMatching(remotePushCommand(), approval.getApprovalId(), "req-1"));
        // 命令变化、requestId 错误、approvalId 不存在都应拒绝
        assertFalse(service.isApprovedAndMatching(
                new ShellExecutor.CommandRequest("git push origin other", ShellExecutor.CommandTypeEnum.remote, "client-a"),
                approval.getApprovalId(), "req-1"));
        assertFalse(service.isApprovedAndMatching(remotePushCommand(), approval.getApprovalId(), "req-other"));
        assertFalse(service.isApprovedAndMatching(remotePushCommand(), "approval-missing", "req-1"));

        // 清理后（例如流断开）复核失败
        service.cancelByRequest("req-1");
        assertFalse(service.isApprovedAndMatching(remotePushCommand(), approval.getApprovalId(), "req-1"));
    }

    @Test
    void requestApprovalFailsFastWithoutRegisteredStream() {
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        AgentStreamBridge bridge = new AgentStreamBridge(); // 未注册任何请求
        PendingApprovalStore store = new PendingApprovalStore();
        CommandApprovalService service = new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

        CommandApprovalResult result = service.requestApproval(context("req-1"), remotePushCommand(), promptReview());
        assertFalse(result.isApproved());
        assertTrue(store.findByRequestId("req-1").isEmpty());
        assertNotNull(result.getReason());
    }
}
