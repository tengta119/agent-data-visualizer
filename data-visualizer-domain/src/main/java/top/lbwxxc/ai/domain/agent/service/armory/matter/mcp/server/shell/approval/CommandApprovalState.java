package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

/**
 * 待审批命令的终态；只能由 PENDING 原子迁移到其余四个终态之一，终态不可重复迁移。
 */
public enum CommandApprovalState {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED
}
