package top.lbwxxc.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;

import java.util.Map;

@Slf4j
@Service("myLogPlugin")
public class MyLogPlugin extends LoggingPlugin {

    private final String requestId;
    private final AgentStreamBridge agentStreamBridge;

    public MyLogPlugin() {
        super("MyLogPlugin");
        this.requestId = null;
        this.agentStreamBridge = null;
    }

    public MyLogPlugin(String requestId, AgentStreamBridge agentStreamBridge) {
        super("MyLogPlugin");
        this.requestId = requestId;
        this.agentStreamBridge = agentStreamBridge;
    }

    @Override
    public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
        emitLog("run", "开始执行本次智能体任务");
        log.info("开始执行本次智能体任务");
        return super.beforeRunCallback(invocationContext);
    }

    @Override
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        emitLog("run", "收到用户消息");
        log.info("收到用户消息");
        return super.onUserMessageCallback(invocationContext, userMessage);
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        emitLog("agent", "开始执行智能体：" + agent.name());
        log.info("开始执行智能体：{}", agent.name());
        return super.beforeAgentCallback(agent, callbackContext);
    }

    @Override
    public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        emitLog("agent", "智能体执行完成：" + agent.name());
        log.info("智能体执行完成：{}", agent.name());
        return super.afterAgentCallback(agent, callbackContext);
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        emitLog("model", "开始请求大模型");
        return super.beforeModelCallback(callbackContext, llmRequest);
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext callbackContext, LlmResponse llmResponse) {
        emitLog("model", "大模型响应完成");
        return super.afterModelCallback(callbackContext, llmResponse);
    }

    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
        emitLog("tool", "开始调用工具：" + tool.name());
        return super.beforeToolCallback(tool, toolArgs, toolContext);
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Map<String, Object> result) {
        emitLog("tool", "工具调用完成：" + tool.name());
        return super.afterToolCallback(tool, toolArgs, toolContext, result);
    }

    @Override
    public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
        emitLog("run", "收到执行事件");
        return super.onEventCallback(invocationContext, event);
    }

    @Override
    public Maybe<LlmResponse> onModelErrorCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
        emitError("模型调用异常：" + safeMessage(error));
        return super.onModelErrorCallback(callbackContext, llmRequest, error);
    }

    @Override
    public Maybe<Map<String, Object>> onToolErrorCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
        emitError("工具调用异常：" + tool.name() + "，原因：" + safeMessage(error));
        return super.onToolErrorCallback(tool, toolArgs, toolContext, error);
    }

    @Override
    public Completable afterRunCallback(InvocationContext invocationContext) {
        emitLog("run", "智能体任务执行结束");
        return super.afterRunCallback(invocationContext);
    }

    private void emitLog(String stage, String content) {
        if (agentStreamBridge == null || StringUtils.isAnyBlank(requestId, content)) {
            return;
        }

        agentStreamBridge.publishLog(requestId, stage, content);
    }

    private void emitError(String content) {
        if (agentStreamBridge == null || StringUtils.isAnyBlank(requestId, content)) {
            return;
        }

        agentStreamBridge.publishError(requestId, content);
    }

    private String safeMessage(Throwable error) {
        if (error == null || StringUtils.isBlank(error.getMessage())) {
            return "unknown";
        }

        return error.getMessage();
    }
}
