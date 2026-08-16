package top.lbwxxc.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.LoggingPlugin;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;

/**
 * Compacts only the context sent to the current model request.
 *
 * <p>Session events remain untouched. The summary is stored in the ADK State Delta so ADK persists
 * it together with the current invocation event.</p>
 */
@Slf4j
@Service("contextCompactionPlugin")
public class ContextCompactionPlugin extends LoggingPlugin {

    private final String requestId;
    private final AgentStreamBridge agentStreamBridge;

    public ContextCompactionPlugin() {
        super("ContextCompactionPlugin");
        this.requestId = null;
        this.agentStreamBridge = null;
    }

    public  ContextCompactionPlugin(String requestId, AgentStreamBridge agentStreamBridge) {
        super("ContextCompactionPlugin");
        this.requestId = requestId;
        this.agentStreamBridge = agentStreamBridge;
    }


    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        log.info(
                "模型上下文压缩评估 agent:{} sessionId:{} requestContents:{} sessionEvents:{}",
                callbackContext.agentName(),
                callbackContext.sessionId(),
                llmRequest.build().contents().size(),
                callbackContext.events().size()
        );
        emitLog("run", "模型上下文压缩评估 agent:" + callbackContext.agentName() +
                "sessionId:" + callbackContext.sessionId() +
                "requestContents:" + llmRequest.build().contents().size() +
                "sessionEvents:" + callbackContext.events().size());
        try {
            LlmRequest request = llmRequest.build();
            Object previousSummary = callbackContext.state().get(ContextCompactionSupport.CONTEXT_SUMMARY_KEY);
            String existingSummary = previousSummary instanceof String ? (String) previousSummary : null;

            ContextCompactionSupport.CompactionResult result = ContextCompactionSupport.compact(
                    request.contents(),
                    existingSummary,
                    callbackContext.events().size());

            if (!result.isCompacted()) {
                return Maybe.empty();
            }

            callbackContext.state().put(ContextCompactionSupport.CONTEXT_SUMMARY_KEY, result.getSummary());
            llmRequest.contents(result.getContents());
            log.info("模型上下文已压缩 agent:{} sessionId:{} originalCharacters:{} estimatedTokens:{} retainedContents:{}",
                    callbackContext.agentName(),
                    callbackContext.sessionId(),
                    result.getOriginalCharacterCount(),
                    result.getEstimatedTokenCount(),
                    result.getContents().size());
            emitLog("run", "模型上下文已压缩 agent:" + callbackContext.agentName() +
                    " sessionId:" + callbackContext.sessionId() +
                    "originalCharacters:" + result.getOriginalCharacterCount() +
                    "estimatedTokens:" + result.getEstimatedTokenCount() +
                    "retainedContents" + result.getContents().size());
        } catch (RuntimeException e) {
            // Context compaction is an optimization. Preserve the original request when it cannot
            // be compacted, so a summary failure never blocks the user's primary model request.
            log.warn("模型上下文压缩失败，将使用原始上下文 agent:{} sessionId:{}",
                    callbackContext.agentName(), callbackContext.sessionId(), e);
        }
        return Maybe.empty();
    }

    private void emitLog(String stage, String content) {
        if (agentStreamBridge == null || StringUtils.isAnyBlank(requestId, content)) {
            return;
        }

        agentStreamBridge.publishLog(requestId, stage, content);
    }
}
