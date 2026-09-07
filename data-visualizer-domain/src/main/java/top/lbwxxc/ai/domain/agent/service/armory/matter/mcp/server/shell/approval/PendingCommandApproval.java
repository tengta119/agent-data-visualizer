package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一条待审批命令记录（仅存在于当前 JVM 内存）。
 *
 * <p>状态只能从 PENDING 原子迁移到 APPROVED/REJECTED/EXPIRED/CANCELLED 之一；
 * 每条记录持有独立的 {@link CompletableFuture}，不允许使用全局锁、wait/notify 或共享 Future。</p>
 */
public class PendingCommandApproval {

    private final String approvalId;
    private final String requestId;
    private final String agentId;
    private final String sessionId;
    private final String commandType;
    private final String hostName;
    private final String command;
    private final String commandDigest;
    private final String reason;
    private final long createdAt;
    private final long expiresAt;
    private final AtomicReference<CommandApprovalState> state = new AtomicReference<>(CommandApprovalState.PENDING);
    private final CompletableFuture<CommandApprovalState> future = new CompletableFuture<>();

    public PendingCommandApproval(String approvalId,
                                  String requestId,
                                  String agentId,
                                  String sessionId,
                                  String commandType,
                                  String hostName,
                                  String command,
                                  String commandDigest,
                                  String reason,
                                  long createdAt,
                                  long expiresAt) {
        this.approvalId = approvalId;
        this.requestId = requestId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.commandType = commandType;
        this.hostName = hostName;
        this.command = command;
        this.commandDigest = commandDigest;
        this.reason = reason;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCommandType() {
        return commandType;
    }

    public String getHostName() {
        return hostName;
    }

    public String getCommand() {
        return command;
    }

    public String getCommandDigest() {
        return commandDigest;
    }

    public String getReason() {
        return reason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public CommandApprovalState state() {
        return state.get();
    }

    public boolean isPending() {
        return state.get() == CommandApprovalState.PENDING;
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAt;
    }

    /**
     * CAS 从 PENDING 迁移到终态；成功后完成本条记录的独立 Future。重复调用只有第一次生效。
     */
    public boolean tryTransition(CommandApprovalState target) {
        if (target == CommandApprovalState.PENDING) {
            return false;
        }
        if (!state.compareAndSet(CommandApprovalState.PENDING, target)) {
            return false;
        }
        future.complete(target);
        return true;
    }

    /**
     * 在给定超时内等待用户决定；终态返回后线程被唤醒。
     */
    public CommandApprovalState awaitDecision(long timeoutMillis)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
