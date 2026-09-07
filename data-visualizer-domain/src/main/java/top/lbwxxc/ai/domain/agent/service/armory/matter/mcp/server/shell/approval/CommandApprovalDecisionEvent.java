package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

/**
 * 审批决定事件。Controller 只发布事件，不直接访问等待线程或 Future；
 * 进程内同步监听器调用 {@link CommandApprovalService#resolve} 完成状态校验与原子迁移。
 * 本事件不是持久化消息总线，不承诺崩溃恢复或跨实例传递。
 */
public record CommandApprovalDecisionEvent(
        String requestId,
        String approvalId,
        CommandApprovalDecision decision
) {
}
