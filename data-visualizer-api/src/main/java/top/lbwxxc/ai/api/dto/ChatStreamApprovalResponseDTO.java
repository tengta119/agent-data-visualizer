package top.lbwxxc.ai.api.dto;

import lombok.Data;

/**
 * 审批决定响应。status 取值：approved/rejected/expired/cancelled/already_resolved/
 * not_found/request_mismatch/not_applied；approved 与 rejected 表示本次决定已生效。
 */
@Data
public class ChatStreamApprovalResponseDTO {

    private String requestId;

    private String approvalId;

    private String status;

}
