package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.adapter.port.IBusinessPort;
import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContextHolder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalDecision;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalResult;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalService;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.PendingApprovalStore;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.PendingCommandApproval;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandAuditRecorder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandExecutionPolicyProperties;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyDecision;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReview;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令审批并发上限测试。
 *
 * <p>覆盖两级上限（整个 JVM 与单个 requestId）：超限的命令必须立即返回拒绝结果（在
 * {@link ShellExecutor} 上表现为 {@code forbidden}），既不创建待审批记录、不发送审批事件，
 * 也不进入等待，因此不会占用 Agent 执行线程；审批进入终态或请求被清理后槽位立即释放。</p>
 */
class CommandApprovalConcurrencyLimitTest {

    private final ExecutorService workerPool = Executors.newCachedThreadPool();

    private final CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
    private final PendingApprovalStore store = new PendingApprovalStore();
    private final RecordingAgentStreamBridge bridge = new RecordingAgentStreamBridge();
    private final CommandApprovalService service =
            new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

    @BeforeEach
    void setUp() {
        // 缩短等待，避免实现回归时测试长时间挂起
        properties.setApprovalTimeoutMillis(5000L);
    }

    @AfterEach
    void tearDown() {
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

    private CommandPolicyReview promptReview() {
        return new CommandPolicyReview(CommandPolicyDecision.PROMPT, "命令需要用户审批后执行",
                List.of("git push origin main"));
    }

    private ShellExecutor.CommandRequest promptCommand() {
        return new ShellExecutor.CommandRequest("git push origin main",
                ShellExecutor.CommandTypeEnum.remote, "client-a");
    }

    private CommandExecutionContext context(String requestId) {
        return new CommandExecutionContext(requestId, "agent-1", "user-1", "sess-" + requestId);
    }

    private Future<CommandApprovalResult> submitApproval(String requestId) {
        return workerPool.submit(() -> {
            return service.requestApproval(context(requestId), promptCommand(), promptReview());
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
    void perRequestPendingLimitRejectsImmediatelyWithoutParkingOrExtraEvent() throws Exception {
        Future<CommandApprovalResult> waiting = submitApproval("req-1");
        awaitUntil(() -> store.countPendingByRequest("req-1") == 1, 2000);

        long start = System.currentTimeMillis();
        CommandApprovalResult overLimit = service.requestApproval(context("req-1"), promptCommand(), promptReview());
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(overLimit.isApproved());
        assertNotNull(overLimit.getReason());
        assertTrue(elapsed < 1000, "单请求超限应直接拒绝而不进入等待，实际耗时 " + elapsed + "ms");
        assertEquals(1, store.countPendingByRequest("req-1"));
        assertEquals(1, bridge.required.size(), "超限命令不应再发送 approval_required");

        service.cancelByRequest("req-1");
        assertFalse(waiting.get(3, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void globalPendingLimitRejectsOtherRequestsImmediatelyWithoutParking() throws Exception {
        properties.setMaxPendingApprovals(1);
        properties.setMaxPendingApprovalsPerRequest(3);

        Future<CommandApprovalResult> waitingA = submitApproval("req-a");
        awaitUntil(() -> store.countPending() == 1, 2000);

        long start = System.currentTimeMillis();
        CommandApprovalResult overLimit = service.requestApproval(context("req-b"), promptCommand(), promptReview());
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(overLimit.isApproved());
        assertTrue(elapsed < 1000, "全局限额超限应直接拒绝而不进入等待，实际耗时 " + elapsed + "ms");
        assertEquals(1, store.countPending());
        assertTrue(store.findByRequestId("req-b").isEmpty(), "被拒绝的请求不应产生待审批记录");
        assertEquals(1, bridge.required.size());

        service.cancelByRequest("req-a");
        assertFalse(waitingA.get(3, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void slotIsReleasedOnceApprovalReachesTerminalState() throws Exception {
        properties.setMaxPendingApprovals(1);
        properties.setMaxPendingApprovalsPerRequest(3);

        Future<CommandApprovalResult> waitingA = submitApproval("req-a");
        awaitUntil(() -> store.countPending() == 1, 2000);
        PendingCommandApproval approvalA = store.findByRequestId("req-a").get(0);

        assertFalse(service.requestApproval(context("req-b"), promptCommand(), promptReview()).isApproved());

        // A 批准后不再是 PENDING：槽位释放，新请求可再次进入审批
        service.resolve("req-a", approvalA.getApprovalId(), CommandApprovalDecision.APPROVE_ONCE);
        assertTrue(waitingA.get(3, TimeUnit.SECONDS).isApproved());
        assertEquals(0, store.countPending());

        Future<CommandApprovalResult> waitingC = submitApproval("req-c");
        awaitUntil(() -> store.countPendingByRequest("req-c") == 1, 2000);

        service.cancelByRequest("req-c");
        assertFalse(waitingC.get(3, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void cancellingRequestReleasesPendingSlot() throws Exception {
        properties.setMaxPendingApprovals(1);
        properties.setMaxPendingApprovalsPerRequest(3);

        Future<CommandApprovalResult> waitingA = submitApproval("req-a");
        awaitUntil(() -> store.countPending() == 1, 2000);
        assertFalse(service.requestApproval(context("req-b"), promptCommand(), promptReview()).isApproved());

        // 流式请求清理（onCompletion/onTimeout/onError/断开）取消 PENDING 并释放槽位
        service.cancelByRequest("req-a");
        assertEquals(0, store.countPending());
        assertFalse(waitingA.get(3, TimeUnit.SECONDS).isApproved());

        Future<CommandApprovalResult> waitingC = submitApproval("req-c");
        awaitUntil(() -> store.countPendingByRequest("req-c") == 1, 2000);

        service.cancelByRequest("req-c");
        assertFalse(waitingC.get(3, TimeUnit.SECONDS).isApproved());
    }

    @Test
    void overLimitCommandReturnsForbiddenInsteadOfWaiting() throws Exception {
        properties.setRemoteAllowedHosts(List.of("client-a"));
        properties.setRemotePrompt(List.of("git push"));
        properties.setMaxPendingApprovals(5);
        properties.setMaxPendingApprovalsPerRequest(1);

        AtomicInteger actionCalls = new AtomicInteger();
        ShellExecutor executor = new ShellExecutor(countingBusinessPort(actionCalls),
                new CommandPolicyReviewer(properties), new CommandAuditRecorder(), properties, service,
                new ProcessLocalShellLauncher(), new LocalShellRegistry(bridge));

        // 第一个命令进入 PENDING 等待用户决定
        Future<ShellExecutor.CommandResponse> waiting = workerPool.submit(() -> {
            return executeWithContext(executor, "req-1");
        });
        awaitUntil(() -> store.countPendingByRequest("req-1") == 1, 2000);

        // 同一 requestId 的第二个命令超过单请求上限：立即 forbidden，不等待、不执行
        long start = System.currentTimeMillis();
        ShellExecutor.CommandResponse overLimit = executeWithContext(executor, "req-1");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("forbidden", overLimit.getResponseStatus());
        assertTrue(elapsed < 1000, "超限命令不应等待，实际耗时 " + elapsed + "ms");
        assertEquals(0, actionCalls.get());

        service.cancelByRequest("req-1");
        assertEquals("forbidden", waiting.get(3, TimeUnit.SECONDS).getResponseStatus());
        assertEquals(0, actionCalls.get(), "被拒绝与取消的命令都不允许调用网关");
    }

    private ShellExecutor.CommandResponse executeWithContext(ShellExecutor executor, String requestId) {
        CommandExecutionContextHolder.set(context(requestId));
        try {
            return executor.execute(promptCommand());
        } finally {
            CommandExecutionContextHolder.clear();
        }
    }

    private IBusinessPort countingBusinessPort(AtomicInteger actionCalls) {
        return new IBusinessPort() {
            @Override
            public GatewayResponseVO action(GatewayCommandEntity commandEntity) {
                actionCalls.incrementAndGet();
                return GatewayResponseVO.builder().status("success").message("ok").build();
            }

            @Override
            public GatewayResponseVO queryClients() {
                return GatewayResponseVO.builder().status("success").message("client-list").build();
            }
        };
    }
}
