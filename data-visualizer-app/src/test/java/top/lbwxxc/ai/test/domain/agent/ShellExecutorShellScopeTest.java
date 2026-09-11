package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.lbwxxc.ai.domain.agent.adapter.port.IBusinessPort;
import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContextHolder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalDecision;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalService;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.PendingApprovalStore;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandAuditRecorder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandExecutionPolicyProperties;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReviewer;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellHandle;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellLauncher;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellRegistry;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellScope;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellSession;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 Shell 作用域测试（ADR-004）：复用、隔离、请求结束销毁、超时定向销毁、上限淘汰、
 * 空闲回收、拒绝路径零进程。
 *
 * <p>使用内存 Shell 替身（{@link FakeShellLauncher}）而不是真实 Shell，避免依赖操作系统与
 * 真实命令；真实 Shell 的端到端行为由 {@code ShellExecutorLocalExecutionTest} 覆盖。</p>
 *
 * <p>注意：当前源码中所有命令都会进入审批流程，因此测试通过"自动批准 + 把 requestId 标记为活跃"
 * 的桥接器替身驱动执行；不得为了让测试通过而改动策略或审批实现。</p>
 */
class ShellExecutorShellScopeTest {

    private final CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
    private final PendingApprovalStore store = new PendingApprovalStore();
    private final FakeShellLauncher launcher = new FakeShellLauncher();
    private final TestStreamBridge bridge = new TestStreamBridge();
    private final LocalShellRegistry registry = new LocalShellRegistry(bridge);
    private final CommandApprovalService approvalService =
            new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

    @AfterEach
    void tearDown() {
        CommandExecutionContextHolder.clear();
        // 销毁所有内存 Shell 替身，避免其 worker 线程在测试间残留
        registry.destroyAll();
    }

    // ------------------------------------------------------------------ 测试替身

    /**
     * 可控制"请求是否仍活跃"的流式桥接器替身：审批自动批准，活跃集合决定空闲回收能否命中。
     */
    private class TestStreamBridge extends AgentStreamBridge {

        private final Set<String> activeRequests = ConcurrentHashMap.newKeySet();
        private volatile boolean autoResolve = true;
        private volatile CommandApprovalDecision autoDecision = CommandApprovalDecision.APPROVE_ONCE;

        void activate(String requestId) {
            activeRequests.add(requestId);
        }

        void deactivate(String requestId) {
            activeRequests.remove(requestId);
        }

        /** 自动决定的终态；autoResolve=false 时保持 PENDING，用于验证审批超时。 */
        void autoDecide(boolean autoResolve, CommandApprovalDecision decision) {
            this.autoDecision = decision;
            this.autoResolve = autoResolve;
        }

        @Override
        public boolean contains(String requestId) {
            return activeRequests.contains(requestId);
        }

        @Override
        public void publishApprovalRequired(String requestId, String approvalId, String commandType,
                                            String hostName, String command, String reason, Long expiresAt) {
            if (autoResolve) {
                approvalService.resolve(requestId, approvalId, autoDecision);
            }
        }
    }

    /**
     * 内存 Shell 启动器：用管道模拟"写入命令 → 回显结束标记"的交互。
     * {@code respond=false} 时只消费输入、不回显结束标记，用来模拟挂死命令。
     */
    private static class FakeShellLauncher implements LocalShellLauncher {

        private final AtomicInteger launchCount = new AtomicInteger();
        private final List<FakeShellHandle> handles = new CopyOnWriteArrayList<>();
        private volatile boolean respond = true;

        @Override
        public LocalShellHandle launch() {
            FakeShellHandle handle = new FakeShellHandle("fake-sh-" + launchCount.incrementAndGet(), this);
            handles.add(handle);
            handle.start();
            return handle;
        }

        int launchCount() {
            return launchCount.get();
        }

        FakeShellHandle handle(int index) {
            return handles.get(index);
        }

        List<FakeShellHandle> handles() {
            return handles;
        }

        void setRespond(boolean respond) {
            this.respond = respond;
        }
    }

    private static class FakeShellHandle implements LocalShellHandle {

        private static final String POSIX_MARKER_PREFIX = "printf '%s\\n' '";

        private final String name;
        private final FakeShellLauncher owner;
        private final PipedWriter commandSink = new PipedWriter();
        private final PipedReader commandSource;
        private final PipedWriter responseSink = new PipedWriter();
        private final PipedReader responseSource;
        private final BufferedWriter commandWriter;
        private final BufferedReader responseReader;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final List<String> receivedCommands = new CopyOnWriteArrayList<>();
        private final Thread worker;

        FakeShellHandle(String name, FakeShellLauncher owner) {
            this.name = name;
            this.owner = owner;
            try {
                this.commandSource = new PipedReader(commandSink, 8192);
                this.responseSource = new PipedReader(responseSink, 8192);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            this.commandWriter = new BufferedWriter(commandSink);
            this.responseReader = new BufferedReader(responseSource);
            this.worker = new Thread(this::runShellLoop, "fake-shell-" + name);
            this.worker.setDaemon(true);
        }

        void start() {
            worker.start();
        }

        @Override
        public BufferedWriter writer() {
            return commandWriter;
        }

        @Override
        public BufferedReader reader() {
            return responseReader;
        }

        @Override
        public String shellName() {
            return name;
        }

        @Override
        public String endMarkerCommand(String marker) {
            return POSIX_MARKER_PREFIX + marker + "'";
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public void destroyForcibly() {
            alive.set(false);
            closeQuietly(responseSink);
            closeQuietly(commandSink);
            worker.interrupt();
        }

        List<String> receivedCommands() {
            return receivedCommands;
        }

        private void runShellLoop() {
            try (BufferedReader in = new BufferedReader(commandSource);
                 BufferedWriter out = new BufferedWriter(responseSink)) {
                String line;
                while ((line = in.readLine()) != null && alive.get()) {
                    String marker = markerOf(line);
                    if (marker != null) {
                        if (owner.respond) {
                            out.write(marker);
                            out.newLine();
                            out.flush();
                        }
                        continue;
                    }
                    receivedCommands.add(line);
                    if (owner.respond) {
                        out.write("FAKE_OUTPUT:" + line);
                        out.newLine();
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // 管道关闭：等价于 Shell 退出
            }
        }

        private static String markerOf(String line) {
            if (line.startsWith(POSIX_MARKER_PREFIX) && line.endsWith("'")) {
                return line.substring(POSIX_MARKER_PREFIX.length(), line.length() - 1);
            }
            return null;
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 关闭失败不影响销毁语义
            }
        }
    }

    private static class NoopBusinessPort implements IBusinessPort {

        @Override
        public GatewayResponseVO action(GatewayCommandEntity commandEntity) {
            return GatewayResponseVO.builder().status("success").message("gateway-ok").build();
        }

        @Override
        public GatewayResponseVO queryClients() {
            return GatewayResponseVO.builder().status("success").message("client-list").build();
        }
    }

    // ------------------------------------------------------------------ 测试工具

    private ShellExecutor newExecutor() {
        return new ShellExecutor(new NoopBusinessPort(), new CommandPolicyReviewer(properties),
                new CommandAuditRecorder(), properties, approvalService, launcher, registry);
    }

    private ShellExecutor.CommandResponse run(ShellExecutor executor, String requestId, String command) {
        return run(executor, requestId, command, ShellExecutor.CommandTypeEnum.local, "");
    }

    private ShellExecutor.CommandResponse run(ShellExecutor executor, String requestId, String command,
                                             ShellExecutor.CommandTypeEnum type, String host) {
        bridge.activate(requestId);
        CommandExecutionContextHolder.set(new CommandExecutionContext(requestId, "agent-1", "user-1", "sess-" + requestId));
        try {
            return executor.execute(new ShellExecutor.CommandRequest(command, type, host));
        } finally {
            CommandExecutionContextHolder.clear();
        }
    }

    private ShellExecutor.CommandResponse runWithoutContext(ShellExecutor executor, String command) {
        return executor.execute(new ShellExecutor.CommandRequest(
                command, ShellExecutor.CommandTypeEnum.local, ""));
    }

    /** 有请求上下文但流式连接已不存在（桥接器不含该 requestId）。 */
    private ShellExecutor.CommandResponse runWithContextOnly(ShellExecutor executor, String requestId, String command) {
        CommandExecutionContextHolder.set(new CommandExecutionContext(requestId, "agent-1", "user-1", "sess-" + requestId));
        try {
            return executor.execute(new ShellExecutor.CommandRequest(
                    command, ShellExecutor.CommandTypeEnum.local, ""));
        } finally {
            CommandExecutionContextHolder.clear();
        }
    }

    private LocalShellSession session(String requestId) {
        return registry.get(requestId);
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

    // ------------------------------------------------------------------ 作用域 key

    @Test
    void scopeKeyIsRequestIdWhenContextPresent() {
        CommandExecutionContextHolder.set(new CommandExecutionContext("req-1", "agent-1", "user-1", "sess-1"));
        try {
            LocalShellScope scope = LocalShellScope.resolve();
            assertEquals("req-1", scope.key());
            assertFalse(scope.ephemeral());
        } finally {
            CommandExecutionContextHolder.clear();
        }
    }

    @Test
    void scopeKeyIsEphemeralWhenContextMissing() {
        LocalShellScope first = LocalShellScope.resolve();
        LocalShellScope second = LocalShellScope.resolve();

        assertTrue(first.ephemeral());
        assertTrue(second.ephemeral());
        // 无上下文时每次都是独立的一次性作用域，不会退化为全局共享 Shell
        assertNotEquals(first.key(), second.key());
        assertNotNull(first.key());
    }

    // ------------------------------------------------------------------ 复用与隔离

    @Test
    void sameRequestReusesOneShellAcrossCommands() {
        ShellExecutor executor = newExecutor();

        assertEquals("success", run(executor, "req-1", "pwd").getResponseStatus());
        assertEquals("success", run(executor, "req-1", "uname").getResponseStatus());

        assertEquals(1, launcher.launchCount(), "同一请求的两条命令只能创建一个 Shell");
        assertEquals(1, executor.localShellCount());
        assertEquals(List.of("pwd", "uname"), launcher.handle(0).receivedCommands());
    }

    @Test
    void differentRequestsDoNotShareShell() {
        ShellExecutor executor = newExecutor();

        assertEquals("success", run(executor, "req-a", "pwd").getResponseStatus());
        assertEquals("success", run(executor, "req-b", "uname").getResponseStatus());

        assertEquals(2, launcher.launchCount(), "不同请求必须各自持有独立 Shell");
        assertEquals(2, executor.localShellCount());
        assertEquals(List.of("pwd"), launcher.handle(0).receivedCommands());
        assertEquals(List.of("uname"), launcher.handle(1).receivedCommands());
        assertNotEquals(session("req-a").shellName(), session("req-b").shellName());
    }

    // ------------------------------------------------------------------ 请求结束销毁

    @Test
    void closeRequestShellDestroysOnlyThatRequestAndIsIdempotent() {
        ShellExecutor executor = newExecutor();
        run(executor, "req-a", "pwd");
        run(executor, "req-b", "pwd");

        FakeShellHandle shellA = launcher.handle(0);

        assertTrue(executor.closeRequestShell("req-a"));
        assertNull(session("req-a"));
        assertFalse(shellA.isAlive());
        assertEquals(1, executor.localShellCount());

        // 幂等：重复调用与"该请求没有 Shell"都不产生副作用、不抛异常
        assertFalse(executor.closeRequestShell("req-a"));
        assertFalse(executor.closeRequestShell("req-never-existed"));
        assertFalse(executor.closeShell("", "request_end"));
        assertEquals(1, executor.localShellCount());
        assertTrue(session("req-b").isAlive());
    }

    @Test
    void shutdownDestroysAllShells() {
        ShellExecutor executor = newExecutor();
        run(executor, "req-a", "pwd");
        run(executor, "req-b", "pwd");
        assertEquals(2, executor.localShellCount());

        executor.shutdown();

        assertEquals(0, executor.localShellCount());
        assertFalse(launcher.handle(0).isAlive());
        assertFalse(launcher.handle(1).isAlive());
    }

    // ------------------------------------------------------------------ 超时定向销毁

    @Test
    @Timeout(60)
    void timeoutDestroysOnlyOwnShellAndOtherRequestKeepsWorking() {
        properties.setLocalExecutionTimeoutMillis(300L);
        ShellExecutor executor = newExecutor();

        assertEquals("success", run(executor, "req-a", "pwd").getResponseStatus());
        assertEquals("success", run(executor, "req-b", "pwd").getResponseStatus());
        FakeShellHandle shellA = launcher.handle(0);
        FakeShellHandle shellB = launcher.handle(1);

        // 让 req-a 的命令挂死：只销毁 req-a 的 Shell
        launcher.setRespond(false);
        assertEquals("timeout", run(executor, "req-a", "pwd").getResponseStatus());
        assertFalse(shellA.isAlive(), "超时必须销毁触发超时的那个请求的 Shell");
        assertNull(session("req-a"));

        assertTrue(shellB.isAlive(), "其他请求的 Shell 不受超时影响");
        assertEquals(1, executor.localShellCount());

        // 其他请求继续复用各自的 Shell，不被超时波及
        launcher.setRespond(true);
        assertEquals("success", run(executor, "req-b", "uname").getResponseStatus());
        assertEquals(2, launcher.launchCount(), "req-b 应复用原 Shell 而不是重建");
        assertEquals(List.of("pwd", "uname"), shellB.receivedCommands());
    }

    // ------------------------------------------------------------------ 上限与空闲回收

    @Test
    void shellCountLimitEvictsLeastRecentlyUsedIdleShell() {
        properties.setMaxConcurrentShells(1);
        properties.setShellIdleTimeoutMillis(60_000L);
        ShellExecutor executor = newExecutor();

        assertEquals("success", run(executor, "req-a", "pwd").getResponseStatus());
        FakeShellHandle shellA = launcher.handle(0);

        assertEquals("success", run(executor, "req-b", "pwd").getResponseStatus());

        assertEquals(2, launcher.launchCount());
        assertEquals(1, executor.localShellCount(), "存活 Shell 数不得超过上限");
        assertFalse(shellA.isAlive(), "达到上限时应淘汰最久未使用的空闲 Shell");
        assertNull(session("req-a"));
        assertTrue(session("req-b").isAlive());
    }

    @Test
    @Timeout(60)
    void unavailableWhenNoIdleShellCanBeEvicted() throws Exception {
        properties.setMaxConcurrentShells(1);
        ShellExecutor holder = newExecutor();
        ShellExecutor competitor = newExecutor();

        // holder 的命令挂死：其 Shell 处于"使用中"，无法被淘汰
        launcher.setRespond(false);
        bridge.activate("req-holder");
        Thread holderThread = new Thread(() -> run(holder, "req-holder", "pwd"), "holder-request");
        holderThread.setDaemon(true);
        holderThread.start();
        awaitUntil(() -> {
            LocalShellSession inUse = registry.get("req-holder");
            return inUse != null && inUse.isInUse();
        }, 2000);

        ShellExecutor.CommandResponse response = run(competitor, "req-other", "pwd");

        assertEquals("unavailable", response.getResponseStatus());
        assertTrue(response.getResponseMessage().contains("上限"));
        assertEquals(1, launcher.launchCount(), "无可淘汰项时不得创建新 Shell");

        // 清理：销毁占用的 Shell 让挂死线程退出
        holder.closeShell("req-holder", "test_cleanup");
        holderThread.join(3000);
        assertFalse(holderThread.isAlive());
    }

    @Test
    void idleSweepReclaimsShellOfInactiveRequest() throws Exception {
        properties.setShellIdleTimeoutMillis(50L);
        properties.setMaxConcurrentShells(8);
        ShellExecutor executor = newExecutor();

        assertEquals("success", run(executor, "req-a", "pwd").getResponseStatus());
        FakeShellHandle shellA = launcher.handle(0);

        // 模拟请求已经结束（流式上下文已清理）：空闲清扫可以回收
        bridge.deactivate("req-a");
        Thread.sleep(120L);

        assertEquals("success", run(executor, "req-b", "pwd").getResponseStatus());

        assertFalse(shellA.isAlive(), "超过空闲阈值且请求不再活跃的 Shell 应被回收");
        assertNull(session("req-a"));
        assertEquals(1, executor.localShellCount());
        assertEquals(2, launcher.launchCount());
    }

    @Test
    void idleSweepKeepsShellOfActiveRequest() throws Exception {
        properties.setShellIdleTimeoutMillis(50L);
        properties.setMaxConcurrentShells(8);
        ShellExecutor executor = newExecutor();

        assertEquals("success", run(executor, "req-a", "pwd").getResponseStatus());
        FakeShellHandle shellA = launcher.handle(0);

        // 请求仍然活跃：即使空闲超过阈值也不得回收（模型可能只是思考较久）
        Thread.sleep(120L);
        assertEquals("success", run(executor, "req-b", "pwd").getResponseStatus());

        assertTrue(shellA.isAlive(), "活跃请求的 Shell 不得被空闲回收");
        assertNotNull(session("req-a"));
        assertEquals(2, executor.localShellCount());
    }

    @Test
    void invalidShellLimitsFallBackToSafeDefaults() {
        properties.setMaxConcurrentShells(0);
        properties.setShellIdleTimeoutMillis(0L);
        assertTrue(properties.resolveMaxConcurrentShells() > 0, "非法上限不得解释为无上限");
        assertTrue(properties.resolveShellIdleTimeoutMillis() > 0, "非法空闲阈值不得解释为永不回收");

        properties.setMaxConcurrentShells(-1);
        properties.setShellIdleTimeoutMillis(-1L);
        assertTrue(properties.resolveMaxConcurrentShells() > 0);
        assertTrue(properties.resolveShellIdleTimeoutMillis() > 0);
    }

    // ------------------------------------------------------------------ 拒绝路径零进程

    @Test
    void approvalRejectedCreatesNoShell() {
        ShellExecutor executor = newExecutor();
        bridge.autoDecide(true, CommandApprovalDecision.REJECT);

        ShellExecutor.CommandResponse response = run(executor, "req-1", "pwd");

        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, launcher.launchCount(), "审批被拒绝不得创建 Shell");
        assertEquals(0, executor.localShellCount());
    }

    @Test
    void approvalExpiredCreatesNoShell() {
        properties.setApprovalTimeoutMillis(200L);
        ShellExecutor executor = newExecutor();
        // 保持 PENDING：审批等待超时后命令必须安全失败
        bridge.autoDecide(false, CommandApprovalDecision.APPROVE_ONCE);

        ShellExecutor.CommandResponse response = run(executor, "req-1", "pwd");

        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, launcher.launchCount(), "审批过期不得创建 Shell");
        assertEquals(0, executor.localShellCount());
    }

    @Test
    void missingStreamContextCreatesNoShell() {
        ShellExecutor executor = newExecutor();
        // 有 ThreadLocal 上下文但桥接器不含该 requestId：审批前置检查失败，命令被拒绝
        ShellExecutor.CommandResponse response = runWithContextOnly(executor, "req-1", "pwd");

        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, launcher.launchCount());
        assertEquals(0, executor.localShellCount());
    }

    @Test
    void noContextLocalCommandCreatesNoShellBecauseApprovalFailsFast() {
        ShellExecutor executor = newExecutor();

        ShellExecutor.CommandResponse response = runWithoutContext(executor, "pwd");

        // 当前实现下所有命令都要审批，无 ThreadLocal 上下文时审批安全失败，因此不会走到本地执行
        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, launcher.launchCount());
        assertEquals(0, executor.localShellCount());
    }

    @Test
    void remoteCommandAndClientsQueryCreateNoShell() {
        properties.setRemoteAllowedHosts(List.of("client-a"));
        ShellExecutor executor = newExecutor();
        bridge.activate("req-remote");

        ShellExecutor.CommandResponse remote = run(executor, "req-remote", "pwd",
                ShellExecutor.CommandTypeEnum.remote, "client-a");
        ShellExecutor.CommandResponse clients = run(executor, "req-remote", "clients");

        assertEquals("success", remote.getResponseStatus());
        assertEquals("success", clients.getResponseStatus());
        assertEquals(0, launcher.launchCount(), "remote 与 clients 都不得创建本地 Shell");
        assertEquals(0, executor.localShellCount());
    }

    // ------------------------------------------------------------------ 故障路径

    @Test
    @Timeout(60)
    void shellExitedUnexpectedlyIsDestroyedAndRebuilt() {
        ShellExecutor executor = newExecutor();
        assertEquals("success", run(executor, "req-a", "pwd").getResponseStatus());
        FakeShellHandle shellA = launcher.handle(0);

        // 模拟 Shell 进程自行退出（非超时）
        shellA.destroyForcibly();
        assertEquals("success", run(executor, "req-a", "uname").getResponseStatus());

        assertEquals(2, launcher.launchCount(), "进程退出后应重建而不是复用死进程");
        assertEquals(1, executor.localShellCount());
        assertNotEquals(shellA.shellName(), session("req-a").shellName());
    }
}
