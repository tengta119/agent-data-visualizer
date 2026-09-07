package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

import lombok.Value;

/**
 * ShellExecutor 等待审批后的结果。只有 state == APPROVED 才能进入二次审查与执行；
 * 用户批准只表示同意本次继续尝试，不表示命令已经成功执行。
 */
@Value
public class CommandApprovalResult {

    CommandApprovalState state;
    String approvalId;
    String reason;

    public boolean isApproved() {
        return state == CommandApprovalState.APPROVED;
    }

    public static CommandApprovalResult notApplied(String reason) {
        return new CommandApprovalResult(CommandApprovalState.CANCELLED, null, reason);
    }
}
