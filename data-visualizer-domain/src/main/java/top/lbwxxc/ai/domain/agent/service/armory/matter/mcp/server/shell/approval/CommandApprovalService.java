package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandAuditRecorder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandExecutionPolicyProperties;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReview;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandSensitiveRedactor;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 命令交互审批服务。
 *
 * <p>职责：创建 approvalId 与命令摘要 → 保存 PendingApproval → 通过
 * {@link AgentStreamBridge} 发布 approval_required → 在配置超时内等待独立 Future →
 * 接收并校验审批决定（approve_once/reject）→ 发布 approval_resolved →
 * 处理拒绝、过期、取消与重复决定 → 记录审计。</p>
 *
 * <p>审批状态仅存于当前 JVM，ApplicationEvent 只做进程内同步唤醒，
 * 不作为持久化事件总线；重启或多实例会丢失待审批记录。</p>
 */
@Slf4j
@Service
public class CommandApprovalService {

    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;
    private static final long DEFAULT_TIMEOUT_MAX_MILLIS = 300_000L;

    private final PendingApprovalStore store;
    private final AgentStreamBridge agentStreamBridge;
    private final CommandExecutionPolicyProperties policyProperties;
    private final CommandAuditRecorder auditRecorder;

    public CommandApprovalService(PendingApprovalStore store,
                                  AgentStreamBridge agentStreamBridge,
                                  CommandExecutionPolicyProperties policyProperties,
                                  CommandAuditRecorder auditRecorder) {
        this.store = store;
        this.agentStreamBridge = agentStreamBridge;
        this.policyProperties = policyProperties;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 为 Prompt 命令创建审批并等待用户决定。返回后只有 state == APPROVED 才允许进入二次审查。
     * 读取不到上下文或流式连接已不存在时安全失败，不等待也不执行。
     */
    public CommandApprovalResult requestApproval(CommandExecutionContext context,
                                                 ShellExecutor.CommandRequest request,
                                                 CommandPolicyReview review) {
        if (context == null || StringUtils.isBlank(context.requestId())) {
            return CommandApprovalResult.notApplied("需要用户审批的命令缺少流式请求上下文，命令未执行");
        }
        String requestId = context.requestId();
        if (!agentStreamBridge.contains(requestId)) {
            return CommandApprovalResult.notApplied("流式连接已断开或不存在，无法进行交互审批，命令未执行");
        }

        long now = System.currentTimeMillis();
        long timeoutMillis = resolveTimeoutMillis();
        String approvalId = "approval-" + UUID.randomUUID().toString().replace("-", "");
        String commandType = request.getCommandType() == null ? "unknown" : request.getCommandType().name().toLowerCase();
        String hostName = request.getHostName() == null ? "" : request.getHostName().trim();
        String displayCommand = CommandSensitiveRedactor.redact(request.getCommand());
        String digest = CommandSnapshotDigest.digest(request);
        String reason = review == null || StringUtils.isBlank(review.getReason())
                ? "该命令需要用户确认后执行" : review.getReason();

        PendingCommandApproval approval = new PendingCommandApproval(
                approvalId, requestId, context.agentId(), context.sessionId(),
                commandType, hostName, displayCommand, digest, reason, now, now + timeoutMillis);
        store.save(approval);
        auditRecorder.recordApproval("APPROVAL_REQUESTED", approvalId, requestId,
                commandType, hostName, displayCommand, reason);

        try {
            agentStreamBridge.publishApprovalRequired(requestId, approvalId, commandType, hostName,
                    displayCommand, reason, approval.getExpiresAt());
        } catch (Exception e) {
            log.warn("审批事件发送失败 approvalId={} requestId={}", approvalId, requestId, e);
            CommandApprovalState state = approval.tryTransition(CommandApprovalState.CANCELLED)
                    ? CommandApprovalState.CANCELLED : approval.state();
            auditRecorder.recordApproval("APPROVAL_CANCELLED", approvalId, requestId,
                    commandType, hostName, displayCommand, "审批事件发送失败");
            return new CommandApprovalResult(state, approvalId, "审批事件发送失败，命令未执行");
        }

        CommandApprovalState terminal;
        try {
            terminal = approval.awaitDecision(timeoutMillis);
        } catch (TimeoutException e) {
            terminal = transitionLocally(approval, CommandApprovalState.EXPIRED, "审批等待超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminal = transitionLocally(approval, CommandApprovalState.CANCELLED, "审批等待被中断");
        } catch (ExecutionException e) {
            terminal = transitionLocally(approval, CommandApprovalState.CANCELLED, "审批等待异常");
        }
        return new CommandApprovalResult(terminal, approvalId, describe(terminal));
    }

    /**
     * 校验 requestId + approvalId + decision 并原子完成审批。由审批决定事件的同步监听器调用。
     */
    public CommandApprovalResolveResult resolve(String requestId, String approvalId, CommandApprovalDecision decision) {
        PendingCommandApproval approval = store.get(approvalId);
        if (approval == null) {
            return CommandApprovalResolveResult.notFound();
        }
        if (!requestId.equals(approval.getRequestId())) {
            return CommandApprovalResolveResult.requestMismatch();
        }

        if (approval.isPending() && approval.isExpired(System.currentTimeMillis())
                && approval.tryTransition(CommandApprovalState.EXPIRED)) {
            publishResolved(approval, CommandApprovalState.EXPIRED);
            auditResolved(approval, CommandApprovalState.EXPIRED);
        }
        if (!approval.isPending()) {
            return outcomeFor(approval, decision);
        }

        CommandApprovalState target = decision == CommandApprovalDecision.REJECT
                ? CommandApprovalState.REJECTED : CommandApprovalState.APPROVED;
        if (approval.tryTransition(target)) {
            publishResolved(approval, target);
            auditResolved(approval, target);
        }
        return outcomeFor(approval, decision);
    }

    /**
     * Controller 在发布同步事件后读取的只读结果，便于组装响应且不与等待线程/清理竞态。
     */
    public CommandApprovalResolveResult decisionOutcome(String requestId, String approvalId,
                                                        CommandApprovalDecision decision) {
        PendingCommandApproval approval = store.get(approvalId);
        if (approval == null) {
            return CommandApprovalResolveResult.notFound();
        }
        if (!requestId.equals(approval.getRequestId())) {
            return CommandApprovalResolveResult.requestMismatch();
        }
        return outcomeFor(approval, decision);
    }

    /**
     * 取消某个 requestId 下仍处于 PENDING 的审批并释放等待 Future；终态记录一并清理。幂等。
     * 由流式 onCompletion/onTimeout/onError 与客户端断开触发。
     */
    public void cancelByRequest(String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return;
        }
        for (PendingCommandApproval approval : store.findByRequestId(requestId)) {
            if (approval.tryTransition(CommandApprovalState.CANCELLED)) {
                publishResolved(approval, CommandApprovalState.CANCELLED);
                auditResolved(approval, CommandApprovalState.CANCELLED);
            }
        }
        store.removeByRequest(requestId);
    }

    /**
     * 执行前的最终复核：审批记录仍存在且为 APPROVED、requestId 匹配、命令快照摘要一致。
     * 记录被清理（请求取消/流断开）或摘要不一致时返回 false，禁止进入 local/remote 执行器。
     */
    public boolean isApprovedAndMatching(ShellExecutor.CommandRequest request, String approvalId, String requestId) {
        if (request == null || StringUtils.isAnyBlank(requestId, approvalId)) {
            return false;
        }
        PendingCommandApproval approval = store.get(approvalId);
        if (approval == null || approval.state() != CommandApprovalState.APPROVED) {
            return false;
        }
        if (!requestId.equals(approval.getRequestId())) {
            return false;
        }
        return CommandSnapshotDigest.digest(request).equals(approval.getCommandDigest());
    }

    @EventListener
    public void handleDecisionEvent(CommandApprovalDecisionEvent event) {
        if (event == null) {
            return;
        }
        resolve(event.requestId(), event.approvalId(), event.decision());
    }

    private CommandApprovalState transitionLocally(PendingCommandApproval approval,
                                                   CommandApprovalState target,
                                                   String reason) {
        if (approval.tryTransition(target)) {
            publishResolved(approval, target);
            auditResolved(approval, target, reason);
            return target;
        }
        return approval.state();
    }

    private CommandApprovalResolveResult outcomeFor(PendingCommandApproval approval, CommandApprovalDecision decision) {
        CommandApprovalState state = approval.state();
        switch (state) {
            case APPROVED:
                return decision == CommandApprovalDecision.REJECT
                        ? CommandApprovalResolveResult.alreadyResolved(state)
                        : CommandApprovalResolveResult.approved();
            case REJECTED:
                return decision == CommandApprovalDecision.APPROVE_ONCE
                        ? CommandApprovalResolveResult.alreadyResolved(state)
                        : CommandApprovalResolveResult.rejected();
            case EXPIRED:
                return CommandApprovalResolveResult.expired();
            case CANCELLED:
                return CommandApprovalResolveResult.cancelled();
            default:
                return CommandApprovalResolveResult.notApplied();
        }
    }

    private void publishResolved(PendingCommandApproval approval, CommandApprovalState state) {
        try {
            agentStreamBridge.publishApprovalResolved(approval.getRequestId(), approval.getApprovalId(),
                    state.name().toLowerCase());
        } catch (Exception e) {
            log.warn("审批结果事件发送失败 approvalId={} requestId={}", approval.getApprovalId(),
                    approval.getRequestId(), e);
        }
    }

    private void auditResolved(PendingCommandApproval approval, CommandApprovalState state) {
        auditResolved(approval, state, describe(state));
    }

    private void auditResolved(PendingCommandApproval approval, CommandApprovalState state, String reason) {
        auditRecorder.recordApproval("APPROVAL_" + state.name(), approval.getApprovalId(),
                approval.getRequestId(), approval.getCommandType(), approval.getHostName(),
                approval.getCommand(), reason);
    }

    private String describe(CommandApprovalState state) {
        switch (state) {
            case APPROVED:
                return "审批已批准";
            case REJECTED:
                return "审批被拒绝";
            case EXPIRED:
                return "审批已过期";
            case CANCELLED:
                return "审批已取消";
            default:
                return "审批未完成";
        }
    }

    private long resolveTimeoutMillis() {
        long timeout = policyProperties.getApprovalTimeoutMillis();
        long max = policyProperties.getApprovalTimeoutMillisMax();
        if (timeout <= 0) {
            timeout = DEFAULT_TIMEOUT_MILLIS;
        }
        if (max <= 0) {
            max = DEFAULT_TIMEOUT_MAX_MILLIS;
        }
        long resolved = Math.min(timeout, max);
        return Math.max(resolved, 1L);
    }
}
