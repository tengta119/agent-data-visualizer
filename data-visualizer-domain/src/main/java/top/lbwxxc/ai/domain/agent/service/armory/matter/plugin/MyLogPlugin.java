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
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service("myLogPlugin")
public class MyLogPlugin extends LoggingPlugin {

    private static final int MAX_CONTENT_LENGTH = 200;
    private static final int MAX_ARGS_LENGTH = 300;

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
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        emitLog("run", "收到用户消息");
        emitLog("run", "🚀 USER MESSAGE RECEIVED");
        emitLog("run", "   Invocation ID: " + invocationContext.invocationId());
        emitLog("run", "   Session ID: " + invocationContext.session().id());
        emitLog("run", "   User ID: " + invocationContext.userId());
        emitLog("run", "   App Name: " + invocationContext.appName());
        emitLog("run", "   Root Agent: " + invocationContext.agent().name());
        emitLog("run", "   User Content: " + formatContent(Optional.ofNullable(userMessage)));
        invocationContext.branch().ifPresent(branch -> emitLog("run", "   Branch: " + branch));
        return super.onUserMessageCallback(invocationContext, userMessage);
    }

    @Override
    public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
        emitLog("run", "开始执行本次智能体任务");
        emitLog("run", "🏃 调用开始");
        emitLog("run", "   Invocation ID: " + invocationContext.invocationId());
        emitLog("run", "   Starting Agent: " + invocationContext.agent().name());
        return super.beforeRunCallback(invocationContext);
    }

    @Override
    public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
        emitLog("run", "收到执行事件");
        emitLog("run", "📢 EVENT YIELDED");
        emitLog("run", "   Event ID: " + event.id());
        emitLog("run", "   Author: " + event.author());
        emitLog("run", "   Content: " + formatContent(event.content()));
        emitLog("run", "   Final Response: " + event.finalResponse());

        if (!event.functionCalls().isEmpty()) {
            String funcCalls = event.functionCalls().stream()
                    .map(fc -> fc.name().orElse("Unknown"))
                    .collect(Collectors.joining(", "));
            emitLog("run", "   Function Calls: [" + funcCalls + "]");
        }

        if (!event.functionResponses().isEmpty()) {
            String funcResponses = event.functionResponses().stream()
                    .map(fr -> fr.name().orElse("Unknown"))
                    .collect(Collectors.joining(", "));
            emitLog("run", "   Function Responses: [" + funcResponses + "]");
        }

        event.longRunningToolIds().ifPresent(ids -> {
            if (!ids.isEmpty()) {
                emitLog("run", "   Long Running Tools: " + ids);
            }
        });

        return super.onEventCallback(invocationContext, event);
    }

    @Override
    public Completable afterRunCallback(InvocationContext invocationContext) {
        emitLog("run", "智能体任务执行结束");
        emitLog("run", "✅ INVOCATION COMPLETED");
        emitLog("run", "   Invocation ID: " + invocationContext.invocationId());
        emitLog("run", "   Final Agent: " + invocationContext.agent().name());
        return super.afterRunCallback(invocationContext);
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        emitLog("agent", "开始执行智能体：" + agent.name());
        emitLog("agent", "🤖 AGENT STARTING");
        emitLog("agent", "   Agent Name: " + agent.name());
        emitLog("agent", "   Invocation ID: " + callbackContext.invocationId());
        callbackContext.branch().ifPresent(branch -> emitLog("agent", "   Branch: " + branch));
        return super.beforeAgentCallback(agent, callbackContext);
    }

    @Override
    public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        emitLog("agent", "智能体执行完成：" + agent.name());
        emitLog("agent", "🤖 AGENT COMPLETED");
        emitLog("agent", "   Agent Name: " + agent.name());
        emitLog("agent", "   Invocation ID: " + callbackContext.invocationId());
        return super.afterAgentCallback(agent, callbackContext);
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        LlmRequest request = llmRequest.build();
        emitLog("model", "开始请求大模型");
        emitLog("model", "🧠 LLM REQUEST");
        emitLog("model", "   Model: " + request.model().orElse("default"));
        emitLog("model", "   Agent: " + callbackContext.agentName());

        request.getFirstSystemInstruction().ifPresent(sysInstruction -> {
            String truncatedInstruction = sysInstruction;
            if (truncatedInstruction.length() > MAX_CONTENT_LENGTH) {
                truncatedInstruction = truncatedInstruction.substring(0, MAX_CONTENT_LENGTH) + "...";
            }
            emitLog("model", "   System Instruction: '" + truncatedInstruction + "'");
        });

        if (!request.tools().isEmpty()) {
            String toolNames = String.join(", ", request.tools().keySet());
            emitLog("model", "   Available Tools: [" + toolNames + "]");
        }

        return super.beforeModelCallback(callbackContext, llmRequest);
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext callbackContext, LlmResponse llmResponse) {
        emitLog("model", "大模型响应完成");
        emitLog("model", "🧠 LLM RESPONSE");
        emitLog("model", "   Agent: " + callbackContext.agentName());

        if (llmResponse.errorCode().isPresent()) {
            emitLog("model", "   ⚠ ERROR - Code: " + llmResponse.errorCode().get());
            llmResponse.errorMessage().ifPresent(msg -> emitLog("model", "   Error Message: " + msg));
        } else {
            emitLog("model", "   Content: " + formatContent(llmResponse.content()));
            llmResponse.partial().ifPresent(partial -> emitLog("model", "   Partial: " + partial));
            llmResponse.turnComplete().ifPresent(turnComplete -> emitLog("model", "   Turn Complete: " + turnComplete));
        }

        llmResponse.usageMetadata().ifPresent(usage -> emitLog(
                "model",
                "   Token Usage - Input: " + usage.promptTokenCount() + ", Output: " + usage.candidatesTokenCount()
        ));

        return super.afterModelCallback(callbackContext, llmResponse);
    }

    @Override
    public Maybe<LlmResponse> onModelErrorCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
        emitLog("model", "🧠 LLM ERROR");
        emitLog("model", "   Agent: " + callbackContext.agentName());
        emitLog("model", "   Error: " + safeMessage(error));
        emitError("模型调用异常：" + safeMessage(error));
        return super.onModelErrorCallback(callbackContext, llmRequest, error);
    }

    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
        emitLog("tool", "开始调用工具：" + tool.name());
        emitLog("tool", "🛠 TOOL STARTING");
        emitLog("tool", "   Tool Name: " + tool.name());
        emitLog("tool", "   Agent: " + toolContext.agentName());
        toolContext.functionCallId().ifPresent(id -> emitLog("tool", "   Function Call ID: " + id));
        emitLog("tool", "   Arguments: " + formatArgs(toolArgs));
        return super.beforeToolCallback(tool, toolArgs, toolContext);
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Map<String, Object> result) {
        emitLog("tool", "工具调用完成：" + tool.name());
        emitLog("tool", "🛠 TOOL COMPLETED");
        emitLog("tool", "   Tool Name: " + tool.name());
        emitLog("tool", "   Agent: " + toolContext.agentName());
        toolContext.functionCallId().ifPresent(id -> emitLog("tool", "   Function Call ID: " + id));
        emitLog("tool", "   Result: " + formatArgs(result));
        return super.afterToolCallback(tool, toolArgs, toolContext, result);
    }

    @Override
    public Maybe<Map<String, Object>> onToolErrorCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
        emitLog("tool", "🛠 TOOL ERROR");
        emitLog("tool", "   Tool Name: " + tool.name());
        emitLog("tool", "   Agent: " + toolContext.agentName());
        toolContext.functionCallId().ifPresent(id -> emitLog("tool", "   Function Call ID: " + id));
        emitLog("tool", "   Arguments: " + formatArgs(toolArgs));
        emitLog("tool", "   Error: " + safeMessage(error));
        emitError("工具调用异常：" + tool.name() + "，原因：" + safeMessage(error));
        return super.onToolErrorCallback(tool, toolArgs, toolContext, error);
    }

    private String formatContent(Optional<Content> contentOptional) {
        if (contentOptional.isEmpty()) {
            return "None";
        }

        Content content = contentOptional.get();
        if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
            return "None";
        }

        String combinedText = content.parts().get().stream()
                .map(part -> part.text().orElse(""))
                .collect(Collectors.joining("\n"))
                .trim();

        if (combinedText.length() > MAX_CONTENT_LENGTH) {
            return combinedText.substring(0, MAX_CONTENT_LENGTH) + "...";
        }

        return combinedText;
    }

    private String formatArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }

        String argsStr = args.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));

        if (argsStr.length() > MAX_ARGS_LENGTH) {
            return "{" + argsStr.substring(0, MAX_ARGS_LENGTH) + "...}";
        }

        return "{" + argsStr + "}";
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
