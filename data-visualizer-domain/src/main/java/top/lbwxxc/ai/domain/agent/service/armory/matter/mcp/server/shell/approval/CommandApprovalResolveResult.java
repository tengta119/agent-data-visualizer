package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

import lombok.Value;

/**
 * 一次审批决定请求的处理结果；携带状态与可读信息，供 Controller 组装响应与审计。
 */
@Value
public class CommandApprovalResolveResult {

    CommandApprovalResolveStatus status;
    String message;

    public static CommandApprovalResolveResult of(CommandApprovalResolveStatus status, String message) {
        return new CommandApprovalResolveResult(status, message);
    }

    public static CommandApprovalResolveResult approved() {
        return of(CommandApprovalResolveStatus.APPROVED, "审批已批准");
    }

    public static CommandApprovalResolveResult rejected() {
        return of(CommandApprovalResolveStatus.REJECTED, "审批已拒绝");
    }

    public static CommandApprovalResolveResult alreadyResolved(CommandApprovalState state) {
        return of(CommandApprovalResolveStatus.ALREADY_RESOLVED, "审批已被处理，当前状态: " + state.name());
    }

    public static CommandApprovalResolveResult expired() {
        return of(CommandApprovalResolveStatus.EXPIRED, "审批已过期");
    }

    public static CommandApprovalResolveResult cancelled() {
        return of(CommandApprovalResolveStatus.CANCELLED, "审批已取消");
    }

    public static CommandApprovalResolveResult notFound() {
        return of(CommandApprovalResolveStatus.NOT_FOUND, "审批不存在或已被清理");
    }

    public static CommandApprovalResolveResult requestMismatch() {
        return of(CommandApprovalResolveStatus.REQUEST_MISMATCH, "requestId 与 approvalId 不匹配");
    }

    public static CommandApprovalResolveResult notApplied() {
        return of(CommandApprovalResolveStatus.NOT_APPLIED, "审批仍为待处理状态，未生效");
    }
}
