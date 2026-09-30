package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;
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
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellRegistry;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.ProcessLocalShellLauncher;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地命令执行器并发模型测试。
 *
 * <p>覆盖三点：超时解析的夹紧规则；挂死命令在超时后返回 {@code timeout} 且不会永久占用本地 Shell
 * 执行器（后续命令仍可成功）；执行器关闭后 CallerRunsPolicy 的兜底执行被拒绝，返回
 * {@code unavailable} 而不是在调用线程上无超时地写入 Shell。</p>
 */
class ShellExecutorLocalExecutionTest {

    private final CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
    private final PendingApprovalStore store = new PendingApprovalStore();
    private final AutoApprovingAgentStreamBridge bridge = new AutoApprovingAgentStreamBridge();
    private final CommandApprovalService approvalService =
            new CommandApprovalService(store, bridge, properties, new CommandAuditRecorder());

    private ShellExecutor executor;

    @AfterEach
    void tearDown() {
        CommandExecutionContextHolder.clear();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    /**
     * 测试中的普通命令命中 allow 规则；桥接器保留给需要审批的用例使用。
     */
    private class AutoApprovingAgentStreamBridge extends AgentStreamBridge {

        @Override
        public boolean contains(String requestId) {
            return true;
        }

        @Override
        public void publishApprovalRequired(String requestId, String approvalId, String commandType,
                                            String hostName, String command, String reason, Long expiresAt) {
            approvalService.resolve(requestId, approvalId, CommandApprovalDecision.APPROVE_ONCE);
        }
    }

    @Test
    void localExecutionTimeoutIsClampedToConfiguredMax() {
        properties.setLocalExecutionTimeoutMillis(120_000L);
        properties.setLocalExecutionTimeoutMillisMax(30_000L);
        assertEquals(30_000L, properties.resolveLocalExecutionTimeoutMillis());

        properties.setLocalExecutionTimeoutMillis(0L);
        properties.setLocalExecutionTimeoutMillisMax(0L);
        assertEquals(30_000L, properties.resolveLocalExecutionTimeoutMillis());
    }

    @Test
    @Timeout(120)
    void hangingLocalCommandTimesOutAndFollowingCommandStillWorks() throws Exception {
        Assumptions.assumeTrue(localShellAvailable(), "当前环境没有可用的本地 Shell，跳过本地执行测试");
        properties.setLocalExecutionTimeoutMillis(30_000L);
        properties.setLocalAllow(List.of("pwd", "Start-Sleep", "sleep"));
        newExecutor();

        // 预热：先用同一请求的一条普通命令启动该请求的 Shell，避免把 Shell 启动耗时算进后面的超时
        assertEquals("success", executeWithContext(executor, "req-1", "pwd").getResponseStatus());

        properties.setLocalExecutionTimeoutMillis(1_000L);
        long start = System.currentTimeMillis();
        ShellExecutor.CommandResponse timedOut = executeWithContext(executor, "req-1", hangCommand());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("timeout", timedOut.getResponseStatus());
        assertTrue(elapsed < 30_000, "挂死命令不应长期占用调用线程，实际耗时 " + elapsed + "ms");
        assertEquals(0, executor.localShellCount(), "超时后该请求的 Shell 必须被销毁");

        // 超时已销毁该请求的 Shell：后续命令必须能惰性重建并正常执行，不能被挂死命令永久阻塞执行器
        properties.setLocalExecutionTimeoutMillis(30_000L);
        assertEquals("success", executeWithContext(executor, "req-1", "pwd").getResponseStatus());
        assertEquals(1, executor.localShellCount());
    }

    @Test
    void localCommandAfterShutdownIsRefusedOnCallerThread() {
        newExecutor();
        executor.shutdown();

        ShellExecutor.CommandResponse response = executeWithContext(executor, "req-1", "pwd");

        assertEquals("unavailable", response.getResponseStatus());
    }

    private ShellExecutor newExecutor() {
        return executor = new ShellExecutor(new NoopBusinessPort(), new CommandPolicyReviewer(properties),
                new CommandAuditRecorder(), properties, approvalService,
                new ProcessLocalShellLauncher(), new LocalShellRegistry(bridge));
    }

    private ShellExecutor.CommandResponse executeWithContext(ShellExecutor executor, String requestId, String command) {
        CommandExecutionContextHolder.set(new CommandExecutionContext(requestId, "agent-1", "user-1", "sess-" + requestId));
        try {
            return executor.execute(new ShellExecutor.CommandRequest(
                    command, ShellExecutor.CommandTypeEnum.local, ""));
        } finally {
            CommandExecutionContextHolder.clear();
        }
    }

    private static String hangCommand() {
        return isWindows()
                ? "Start-Sleep -Seconds 10"
                : "sleep 10";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean localShellAvailable() {
        String[] candidates = isWindows()
                ? new String[]{"pwsh.exe", "pwsh", "powershell.exe"}
                : new String[]{"bash", "sh"};
        for (String candidate : candidates) {
            try {
                Process process = new ProcessBuilder(candidate).redirectErrorStream(true).start();
                process.destroyForcibly();
                return true;
            } catch (IOException ignored) {
                // 尝试下一个候选 Shell
            }
        }
        return false;
    }

    private static class NoopBusinessPort implements IBusinessPort {

        @Override
        public GatewayResponseVO action(GatewayCommandEntity commandEntity) {
            return GatewayResponseVO.builder().status("error").message("unexpected").build();
        }

        @Override
        public GatewayResponseVO queryClients() {
            return GatewayResponseVO.builder().status("success").message("client-list").build();
        }
    }
}
