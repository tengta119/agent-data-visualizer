package top.lbwxxc.ai.api.dto;

import lombok.Data;

/**
 * 审批决定请求。decision 只允许 approve_once / reject；前端不提交可信命令内容。
 */
@Data
public class ChatStreamApprovalRequestDTO {

    /**
     * 待审批记录标识
     */
    private String approvalId;

    /**
     * 审批决定；approve_once=允许一次；reject=拒绝
     */
    private String decision;

}
