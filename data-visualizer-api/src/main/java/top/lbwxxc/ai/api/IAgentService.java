package top.lbwxxc.ai.api;

import top.lbwxxc.ai.api.dto.*;
import top.lbwxxc.ai.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * 智能体服务接口
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:16
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

    /**
     * 审批决定；仅 chat_stream 请求中的待审批命令支持 approve_once/reject。
     */
    Response<ChatStreamApprovalResponseDTO> decideChatStreamApproval(String requestId,
                                                                     ChatStreamApprovalRequestDTO requestDTO);

}
