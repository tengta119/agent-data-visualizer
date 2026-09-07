package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;

@Slf4j
@Service
public class CommandAuditRecorder {

    public void record(ShellExecutor.CommandRequest request, CommandPolicyReview review, String status) {
        String command = sanitize(request == null ? null : request.getCommand());
        String commandType = request == null || request.getCommandType() == null
                ? "unknown" : request.getCommandType().name();
        String host = request == null || request.getHostName() == null ? "" : request.getHostName();
        log.info("command_audit type={} host={} decision={} status={} reason={} command={}",
                commandType, host, review == null ? "unknown" : review.getDecision(), status,
                review == null ? "unknown" : review.getReason(), command);
    }

    /**
     * 审批生命周期审计，至少区分 APPROVAL_REQUESTED/APPROVAL_APPROVED/APPROVAL_REJECTED/
     * APPROVAL_EXPIRED/APPROVAL_CANCELLED 事件。原始命令与事件都遵循同一脱敏规则。
     */
    public void recordApproval(String event, String approvalId, String requestId,
                               String commandType, String host, String command, String reason) {
        log.info("command_approval_audit event={} approvalId={} requestId={} type={} host={} reason={} command={}",
                event, approvalId, requestId, commandType, host, reason, sanitize(command));
    }

    private String sanitize(String command) {
        if (command == null) return "";
        String sanitized = CommandSensitiveRedactor.redact(command);
        return sanitized.length() > 300 ? sanitized.substring(0, 300) + "..." : sanitized;
    }
}
