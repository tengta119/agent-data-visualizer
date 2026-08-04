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
     * 消息类型；log/result/error/done
     */
    private String type;

    /**
     * 所属阶段；run/agent/model/tool/system
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
     * 消息内容
     */
    private String content;

    /**
     * 事件时间戳
     */
    private Long timestamp;

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

}
