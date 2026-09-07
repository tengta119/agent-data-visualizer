package top.lbwxxc.ai.domain.agent.service.chat.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 智能体流式消息
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamResponseDTO implements Serializable {

    private static final long serialVersionUID = -4867674701850320507L;

    /**
     * 消息类型；log/result/error/done/approval_required/approval_resolved
     */
    private String type;

    /**
     * 所属阶段；run/agent/model/tool/system/approval
     */
    private String stage;

    /**
     * 会话标识
     */
    private String sessionId;

    /**
     * 请求标识
     */
    private String requestId;

    /**
     * 消息内容；审批请求消息中为脱敏后的命令，审批结果消息中为 approved/rejected/expired/cancelled 等状态
     */
    private String content;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 审批标识；仅 approval_required/approval_resolved 消息携带
     */
    private String approvalId;

    /**
     * 命令类型；local/remote，仅 approval_required 消息携带
     */
    private String commandType;

    /**
     * 目标远程终端名称；仅 approval_required 消息携带
     */
    private String hostName;

    /**
     * 审批原因；仅 approval_required 消息携带
     */
    private String reason;

    /**
     * 审批过期时间（毫秒）；仅 approval_required 消息携带
     */
    private Long expiresAt;

    public static AgentStreamResponseDTO log(String sessionId, String requestId, String stage, String content) {
        return AgentStreamResponseDTO.builder()
                .type("log")
                .stage(stage)
                .sessionId(sessionId)
                .requestId(requestId)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentStreamResponseDTO result(String sessionId, String requestId, String content) {
        return AgentStreamResponseDTO.builder()
                .type("result")
                .stage("result")
                .sessionId(sessionId)
                .requestId(requestId)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentStreamResponseDTO error(String sessionId, String requestId, String content) {
        return AgentStreamResponseDTO.builder()
                .type("error")
                .stage("system")
                .sessionId(sessionId)
                .requestId(requestId)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentStreamResponseDTO done(String sessionId, String requestId, String content) {
        return AgentStreamResponseDTO.builder()
                .type("done")
                .stage("system")
                .sessionId(sessionId)
                .requestId(requestId)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 审批请求消息：content 为脱敏后的命令，不得携带 Token、密码、Authorization、私钥或 Secret。
     */
    public static AgentStreamResponseDTO approvalRequired(String requestId, String approvalId,
                                                          String commandType, String hostName,
                                                          String content, String reason, Long expiresAt) {
        return AgentStreamResponseDTO.builder()
                .type("approval_required")
                .stage("approval")
                .requestId(requestId)
                .approvalId(approvalId)
                .commandType(commandType)
                .hostName(hostName)
                .content(content)
                .reason(reason)
                .expiresAt(expiresAt)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 审批结果消息：content 为 approved/rejected/expired/cancelled 等终态。
     */
    public static AgentStreamResponseDTO approvalResolved(String requestId, String approvalId, String status) {
        return AgentStreamResponseDTO.builder()
                .type("approval_resolved")
                .stage("approval")
                .requestId(requestId)
                .approvalId(approvalId)
                .content(status)
                .timestamp(System.currentTimeMillis())
                .build();
    }

}
