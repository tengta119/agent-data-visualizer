package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

/**
 * 审批决定 API 的处理结果状态，用于区分本次决定是否生效及未生效原因。
 */
public enum CommandApprovalResolveStatus {
    APPROVED,
    REJECTED,
    ALREADY_RESOLVED,
    EXPIRED,
    CANCELLED,
    NOT_FOUND,
    REQUEST_MISMATCH,
    NOT_APPLIED
}
