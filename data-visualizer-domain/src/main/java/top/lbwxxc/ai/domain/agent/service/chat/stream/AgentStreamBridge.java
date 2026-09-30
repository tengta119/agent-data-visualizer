package top.lbwxxc.ai.domain.agent.service.chat.stream;


import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能体流式桥接器
 */
@Service
public class AgentStreamBridge {

    private final ConcurrentMap<String, StreamEmitterContext> requestEmitters = new ConcurrentHashMap<>();

    public void register(String sessionId, String requestId, ResponseBodyEmitter emitter) {
        if (StringUtils.isAnyBlank(sessionId, requestId) || emitter == null) {
            return;
        }

        requestEmitters.put(requestId, new StreamEmitterContext(sessionId, emitter));
    }

    public void publish(AgentStreamResponseDTO responseDTO) {
        if (responseDTO == null || StringUtils.isBlank(responseDTO.getRequestId())) {
            return;
        }

        StreamEmitterContext context = requestEmitters.get(responseDTO.getRequestId());
        if (context == null || context.completed.get()) {
            return;
        }

        synchronized (context.sendLock) {
            if (context.completed.get()) {
                return;
            }


            try {
                context.emitter.send(JSON.toJSONString(fillSessionIdIfNecessary(context.sessionId, responseDTO)));
            } catch (Exception e) {
                context.completed.set(true);
                throw new RuntimeException(e);
            }
        }
    }

    public void publishLog(String requestId, String stage, String content) {
        publish(AgentStreamResponseDTO.log(null, requestId, stage, content));
    }

    public void publishResult(String sessionId, String requestId, String content) {
        publish(AgentStreamResponseDTO.result(sessionId, requestId, content));
    }

    public void publishError(String requestId, String content) {
        publish(AgentStreamResponseDTO.error(null, requestId, content));
    }

    public void publishDone(String sessionId, String requestId, String content) {
        publish(AgentStreamResponseDTO.done(sessionId, requestId, content));
    }

    /**
     * 发布审批请求事件；命令必须是已脱敏的展示值。
     */
    public void publishApprovalRequired(String requestId, String approvalId, String commandType,
                                        String hostName, String command, String reason, Long expiresAt) {
        publish(AgentStreamResponseDTO.approvalRequired(requestId, approvalId, commandType,
                hostName, command, reason, expiresAt));
    }

    /**
     * 发布审批结果事件；status 为 approved/rejected/expired/cancelled 等终态。
     */
    public void publishApprovalResolved(String requestId, String approvalId, String status) {
        publish(AgentStreamResponseDTO.approvalResolved(requestId, approvalId, status));
    }

    /**
     * 当前 requestId 是否仍注册了可推送的流式上下文；用于审批创建前的快速失败。
     */
    public boolean contains(String requestId) {
        return StringUtils.isNotBlank(requestId) && requestEmitters.containsKey(requestId);
    }

    public void clear(String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return;
        }

        StreamEmitterContext context = requestEmitters.remove(requestId);
        if (context != null) {
            context.completed.set(true);
        }
    }

    private AgentStreamResponseDTO fillSessionIdIfNecessary(String sessionId, AgentStreamResponseDTO responseDTO) {
        if (StringUtils.isNotBlank(responseDTO.getSessionId()) || StringUtils.isBlank(sessionId)) {
            return responseDTO;
        }

        responseDTO.setSessionId(sessionId);
        return responseDTO;
    }

    private static class StreamEmitterContext {

        private final String sessionId;
        private final ResponseBodyEmitter emitter;
        private final Object sendLock = new Object();
        private final AtomicBoolean completed = new AtomicBoolean(false);

        private StreamEmitterContext(String sessionId, ResponseBodyEmitter emitter) {
            this.sessionId = sessionId;
            this.emitter = emitter;
        }

    }

}
