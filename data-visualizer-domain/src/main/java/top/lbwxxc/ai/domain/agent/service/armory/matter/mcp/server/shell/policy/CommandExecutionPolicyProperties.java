package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "command.execution.policy")
public class CommandExecutionPolicyProperties {

    private static final long LOCAL_EXECUTION_TIMEOUT_DEFAULT_MILLIS = 30_000L;
    private static final long LOCAL_EXECUTION_TIMEOUT_MAX_DEFAULT_MILLIS = 300_000L;
    private static final int MAX_CONCURRENT_SHELLS_DEFAULT = 8;
    private static final long SHELL_IDLE_TIMEOUT_DEFAULT_MILLIS = 1_200_000L;

    private int maxCommandLength = 1000;
    private int maxOutputChars = 1024 * 1024;
    private List<String> localAllow = new ArrayList<>(List.of(
            "clients",
            "pwd",
            "ls",
            "dir",
            "whoami",
            "uname",
            "git status",
            "git diff"
    ));
    private List<String> remoteAllow = new ArrayList<>(List.of(
            "pwd",
            "ls",
            "dir",
            "whoami",
            "uname",
            "git status",
            "git diff"
    ));
    private List<String> remoteAllowedHosts = new ArrayList<>();

    /**
     * 需要用户交互审批的本地命令前缀规则；默认空列表表示不产生审批。
     * 命中 allow 规则的命令优先直接执行，不会进入审批。
     */
    private List<String> localPrompt = new ArrayList<>();

    /**
     * 需要用户交互审批的远程命令前缀规则；默认空列表表示不产生审批。
     */
    private List<String> remotePrompt = new ArrayList<>();

    /**
     * 单次审批等待用户决定的超时时间（毫秒），默认 60 秒。
     */
    private long approvalTimeoutMillis = 60_000L;

    /**
     * 审批等待超时允许配置的最大值（毫秒），默认 300 秒；防止无限延长等待消耗执行线程。
     */
    private long approvalTimeoutMillisMax = 300_000L;

    /**
     * 单次本地命令执行的超时时间（毫秒），默认 30 秒。超时后强制销毁并重启本地 Shell、返回 timeout，
     * 避免一条永远不返回的命令挂死整个本地 Shell 执行器。
     */
    private long localExecutionTimeoutMillis = LOCAL_EXECUTION_TIMEOUT_DEFAULT_MILLIS;

    /**
     * 本地命令执行超时允许配置的最大值（毫秒），默认 300 秒。
     */
    private long localExecutionTimeoutMillisMax = LOCAL_EXECUTION_TIMEOUT_MAX_DEFAULT_MILLIS;

    /**
     * 本地 Shell 交互专用单线程执行器的有界队列容量，默认 64。
     * 队列满时 CallerRunsPolicy 会在调用线程兜底，但任务体检测到非专用执行线程后直接返回 unavailable。
     */
    private int localExecutionQueueCapacity = 64;

    /**
     * 整个 JVM 同时处于 PENDING 的命令审批数量上限，默认 5。
     * 超限的命令直接返回 forbidden，不占用 Agent 执行线程等待审批。
     */
    private int maxPendingApprovals = 5;

    /**
     * 单个 requestId 同时处于 PENDING 的命令审批数量上限，默认 1。
     * 超限的命令直接返回 forbidden，不占用 Agent 执行线程等待审批。
     */
    private int maxPendingApprovalsPerRequest = 1;

    /**
     * 存活本地 Shell 进程数量上限，默认 8。每个 Shell 对应一次请求（或一条无上下文的一次性命令），
     * 达到上限时按 LRU 淘汰最久未使用的空闲 Shell；无法淘汰时命令返回 unavailable。
     */
    private int maxConcurrentShells = MAX_CONCURRENT_SHELLS_DEFAULT;

    /**
     * 本地 Shell 空闲回收阈值（毫秒），默认 20 分钟，与流式 emitter 超时对齐。
     * 只回收“超过阈值、当前空闲且其请求已不再活跃”的 Shell，避免打断模型长思考后的后续命令。
     */
    private long shellIdleTimeoutMillis = SHELL_IDLE_TIMEOUT_DEFAULT_MILLIS;

    /**
     * 解析生效的存活 Shell 数量上限；非法值回退默认值，不得解释为“无上限”。
     */
    public int resolveMaxConcurrentShells() {
        return maxConcurrentShells > 0 ? maxConcurrentShells : MAX_CONCURRENT_SHELLS_DEFAULT;
    }

    /**
     * 解析生效的空闲回收阈值；非法值回退默认值，不得解释为“永不回收”。
     */
    public long resolveShellIdleTimeoutMillis() {
        return shellIdleTimeoutMillis > 0 ? shellIdleTimeoutMillis : SHELL_IDLE_TIMEOUT_DEFAULT_MILLIS;
    }

    /**
     * 解析生效的本地命令执行超时，按 localExecutionTimeoutMillisMax 夹紧，并对非法值回退默认值。
     */
    public long resolveLocalExecutionTimeoutMillis() {
        long timeout = localExecutionTimeoutMillis;
        long max = localExecutionTimeoutMillisMax;
        if (timeout <= 0) {
            timeout = LOCAL_EXECUTION_TIMEOUT_DEFAULT_MILLIS;
        }
        if (max <= 0) {
            max = LOCAL_EXECUTION_TIMEOUT_MAX_DEFAULT_MILLIS;
        }
        return Math.max(Math.min(timeout, max), 1L);
    }
}
