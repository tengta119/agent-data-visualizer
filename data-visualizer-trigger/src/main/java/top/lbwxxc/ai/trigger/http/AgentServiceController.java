package top.lbwxxc.ai.trigger.http;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import top.lbwxxc.ai.api.IAgentService;
import top.lbwxxc.ai.api.dto.*;
import top.lbwxxc.ai.api.response.Response;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.service.IChatService;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalDecision;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalDecisionEvent;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalResolveResult;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalResolveStatus;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalService;
import top.lbwxxc.ai.domain.agent.service.chat.converter.JsonToDrawioConverter;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamResponseDTO;
import top.lbwxxc.ai.domain.agent.service.chat.stream.WorkflowStreamLogPublisher;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowResult;
import top.lbwxxc.ai.types.enums.ResponseCode;
import top.lbwxxc.ai.types.exception.AppException;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private AgentStreamBridge agentStreamBridge;

    @Resource
    private CommandApprovalService commandApprovalService;

    @Resource
    private ShellExecutor shellExecutor;

    @Resource
    private ApplicationEventPublisher applicationEventPublisher;

    private static final Pattern DRAWIO_XML_PATTERN = Pattern.compile("(?s)(<mxfile[\\s\\S]*?</mxfile>|<mxGraphModel[\\s\\S]*?</mxGraphModel>)");
    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "paicli-chat-stream");
        thread.setDaemon(true);
        return thread;
    });

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            PaiCliWorkflowResult result = chatService.handleMessage(
                    requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());
            ChatResponseDTO responseDTO = parseChatResponse(result.content(), result.content());

            log.info("结果返回 {}", responseDTO);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 为每次流式请求建立独立 requestId、Emitter 与命令上下文，再异步运行 PaiCLI 工作流。
     * 正常结束、异常和连接回调统一走 cleanupStream，避免审批或本地 Shell 遗留。
     */
    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(20 * 60 * 1000L);
        AtomicReference<CommandExecutionContext> contextRef = new AtomicReference<>();
        AtomicReference<Future<?>> taskRef = new AtomicReference<>();
        AtomicBoolean cleaned = new AtomicBoolean(false);

        emitter.onCompletion(() -> cleanupStream(cleaned, contextRef.get(), taskRef.get()));
        emitter.onTimeout(() -> cleanupStream(cleaned, contextRef.get(), taskRef.get()));
        emitter.onError(error -> cleanupStream(cleaned, contextRef.get(), taskRef.get()));
        try {
            String sessionId = requestDTO.getSessionId();
            if (StringUtils.isBlank(sessionId)) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
                requestDTO.setSessionId(sessionId);
            }

            log.info("流式对话 agentId:{} userId:{} sessionId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), sessionId);

            final String currentSessionId = sessionId;
            String requestId = UUID.randomUUID().toString();
            final String currentRequestId = requestId;
            CommandExecutionContext context = new CommandExecutionContext(
                    currentRequestId, requestDTO.getAgentId(), requestDTO.getUserId(), currentSessionId);
            contextRef.set(context);
            agentStreamBridge.register(currentSessionId, currentRequestId, emitter);

            Future<?> task = STREAM_EXECUTOR.submit(() -> {
                WorkflowStreamLogPublisher logPublisher = new WorkflowStreamLogPublisher(
                        (stage, content) -> agentStreamBridge.publishLog(currentRequestId, stage, content));
                try {
                    if (cleaned.get()) {
                        return;
                    }
                    PaiCliWorkflowResult result = chatService.handleMessageStream(
                            context.agentId(), context.userId(), context.sessionId(), context,
                            requestDTO.getMessage(), logPublisher);
                    if (cleaned.get()) {
                        return;
                    }
                    logPublisher.flush();
                    ChatResponseDTO response = parseChatResponse(result.content(), result.content());
                    agentStreamBridge.publish(AgentStreamResponseDTO.builder()
                            .type("result")
                            .stage(response.getType())
                            .sessionId(currentSessionId)
                            .requestId(currentRequestId)
                            .content(response.getContent())
                            .timestamp(System.currentTimeMillis())
                            .build());
                    agentStreamBridge.publishDone(currentSessionId, currentRequestId, "completed");
                    emitter.complete();
                } catch (Exception error) {
                    log.error("流式对话失败 sessionId:{} requestId:{}", currentSessionId, currentRequestId, error);
                    if (!cleaned.get()) {
                        try {
                            logPublisher.flush();
                            sendStreamError(currentRequestId, error);
                        } catch (RuntimeException sendFailure) {
                            log.debug("流式错误消息无法发送 requestId:{}", currentRequestId, sendFailure);
                        }
                        emitter.complete();
                    }
                } finally {
                    cleanupStream(cleaned, context, null);
                }
            });
            taskRef.set(task);
            if (cleaned.get()) {
                task.cancel(true);
            }
        } catch (Exception e) {
            log.error("流式对话失败", e);
            cleanupStream(cleaned, contextRef.get(), taskRef.get());
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /** 审批决定按 requestId 与 approvalId 定位；实际状态迁移由同步事件监听器完成。 */
    @Override
    @RequestMapping(value = "chat_stream/{requestId}/approval", method = RequestMethod.POST)
    public Response<ChatStreamApprovalResponseDTO> decideChatStreamApproval(
            @PathVariable("requestId") String requestId,
            @RequestBody ChatStreamApprovalRequestDTO requestDTO) {
        try {
            if (StringUtils.isBlank(requestId)) {
                return Response.<ChatStreamApprovalResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("requestId 不能为空")
                        .build();
            }
            if (requestDTO == null || StringUtils.isBlank(requestDTO.getApprovalId())) {
                return Response.<ChatStreamApprovalResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("approvalId 不能为空")
                        .build();
            }
            CommandApprovalDecision decision = CommandApprovalDecision.fromCode(requestDTO.getDecision());
            if (decision == null) {
                return Response.<ChatStreamApprovalResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("decision 只允许 approve_once 或 reject")
                        .build();
            }

            log.info("审批决定提交 requestId:{} approvalId:{} decision:{}",
                    requestId, requestDTO.getApprovalId(), requestDTO.getDecision());
            // Controller 不直接访问等待线程或 Future；发布同步事件，监听器调用 CommandApprovalService.resolve。
            applicationEventPublisher.publishEvent(new CommandApprovalDecisionEvent(
                    requestId, requestDTO.getApprovalId(), decision));
            CommandApprovalResolveResult outcome = commandApprovalService.decisionOutcome(
                    requestId, requestDTO.getApprovalId(), decision);
            log.info("审批决定处理结果 requestId:{} approvalId:{} outcome:{}",
                    requestId, requestDTO.getApprovalId(), outcome.getStatus());

            ChatStreamApprovalResponseDTO responseDTO = new ChatStreamApprovalResponseDTO();
            responseDTO.setRequestId(requestId);
            responseDTO.setApprovalId(requestDTO.getApprovalId());
            responseDTO.setStatus(statusText(outcome.getStatus()));
            return Response.<ChatStreamApprovalResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("审批决定处理失败 requestId:{}", requestId, e);
            return Response.<ChatStreamApprovalResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private String statusText(CommandApprovalResolveStatus status) {
        if (status == null) {
            return "not_applied";
        }
        switch (status) {
            case APPROVED:
                return "approved";
            case REJECTED:
                return "rejected";
            case EXPIRED:
                return "expired";
            case CANCELLED:
                return "cancelled";
            case ALREADY_RESOLVED:
                return "already_resolved";
            case NOT_FOUND:
                return "not_found";
            case REQUEST_MISMATCH:
                return "request_mismatch";
            default:
                return "not_applied";
        }
    }

    private ChatResponseDTO parseChatResponse(String result, String fallbackContent) {
        String raw = StringUtils.defaultString(result).trim();
        String fallback = StringUtils.defaultIfBlank(fallbackContent, raw);

        ChatResponseDTO parsed = tryParseJsonResponse(raw);
        if (parsed != null) {
            return parsed;
        }

        String drawioXml = extractDrawIoXml(raw);
        if (StringUtils.isNotBlank(drawioXml)) {
            return buildChatResponse("drawio", drawioXml);
        }

        return buildChatResponse("user", fallback);
    }

    private ChatResponseDTO tryParseJsonResponse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }

        List<JsonCandidate> candidates = extractJsonResponses(raw);
        for (int index = candidates.size() - 1; index >= 0; index--) {
            JsonCandidate candidate = candidates.get(index);
            ChatResponseDTO response = candidate.getResponse();
            String type = StringUtils.defaultString(response.getType()).trim().toLowerCase();

            if ("user".equals(type)) {
                return buildChatResponse("user", response.getContent());
            }

            // 新协议：LLM 输出 drawio_graph JSON DSL，由服务端转换器生成 XML（TASK-004）。
            // 转换失败时继续向下检查旧类型与 XML 提取，保证兜底路径不受影响。
            if ("drawio_graph".equals(type)) {
                String drawioXml = JsonToDrawioConverter.tryConvert(candidate.getRawJson());
                if (StringUtils.isNotBlank(drawioXml)) {
                    return buildChatResponse("drawio", drawioXml);
                }
                continue;
            }

            if ("drawio".equals(type) || "drawio_done".equals(type)) {
                String content = StringUtils.defaultString(response.getContent()).trim();
                if (StringUtils.isBlank(content)) {
                    continue;
                }

                String drawioXml = extractDrawIoXml(content);
                return buildChatResponse(
                        "drawio",
                        StringUtils.defaultIfBlank(drawioXml, content)
                );
            }
        }

        return null;
    }

    private List<JsonCandidate> extractJsonResponses(String raw) {
        List<JsonCandidate> responses = new ArrayList<>();
        for (int index = 0; index < raw.length(); index++) {
            if (raw.charAt(index) != '{') {
                continue;
            }

            String candidate = extractBalancedJsonCandidate(raw, index);
            if (StringUtils.isBlank(candidate)) {
                continue;
            }

            try {
                ChatResponseDTO response = JSON.parseObject(candidate, ChatResponseDTO.class);
                if (response != null && isSupportedResponseType(response.getType())) {
                    responses.add(new JsonCandidate(response, candidate));
                }
                index += candidate.length() - 1;
            } catch (Exception e) {
                log.debug("跳过无效 JSON 片段: {}", e.getMessage());
            }
        }

        return responses;
    }

    /** JSON 候选：解析后的响应 DTO + 原始 JSON 文本（drawio_graph 转换需要原始 nodes/edges）。 */
    private static final class JsonCandidate {
        private final ChatResponseDTO response;
        private final String rawJson;

        private JsonCandidate(ChatResponseDTO response, String rawJson) {
            this.response = response;
            this.rawJson = rawJson;
        }

        private ChatResponseDTO getResponse() {
            return response;
        }

        private String getRawJson() {
            return rawJson;
        }
    }

    private boolean isSupportedResponseType(String type) {
        String normalizedType = StringUtils.defaultString(type).trim().toLowerCase();
        return "user".equals(normalizedType)
                || "drawio".equals(normalizedType)
                || "drawio_node".equals(normalizedType)
                || "drawio_edge".equals(normalizedType)
                || "drawio_done".equals(normalizedType)
                || "drawio_graph".equals(normalizedType);
    }

    private String extractBalancedJsonCandidate(String raw, int startIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = startIndex; index < raw.length(); index++) {
            char current = raw.charAt(index);

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (current == '\\') {
                    escaped = true;
                    continue;
                }
                if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
                continue;
            }
            if (current == '{') {
                depth++;
                continue;
            }
            if (current != '}') {
                continue;
            }
            depth--;
            if (depth == 0) {
                return raw.substring(startIndex, index + 1);
            }
        }

        return null;
    }

    private ChatResponseDTO buildChatResponse(String type, String content) {
        ChatResponseDTO responseDTO = new ChatResponseDTO();
        responseDTO.setType(type);
        responseDTO.setContent(StringUtils.defaultString(content));
        return responseDTO;
    }

    private String extractDrawIoXml(String raw) {
        Matcher matcher = DRAWIO_XML_PATTERN.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private void sendStreamError(String requestId, Throwable throwable) {
        String message = throwable == null || StringUtils.isBlank(throwable.getMessage()) ? "unknown" : throwable.getMessage();
        agentStreamBridge.publishError(requestId, message);
    }

    /**
     * 用 cleaned 保证多种终止回调只清理一次，并仅按当前 requestId 取消审批、工作流与 Shell。
     * 不在 Controller 线程清理工具线程的 ThreadLocal；该动作由工具适配器负责。
     */
    private void cleanupStream(AtomicBoolean cleaned, CommandExecutionContext context, Future<?> task) {
        if (!cleaned.compareAndSet(false, true)) {
            return;
        }

        if (context != null) {
            try {
                commandApprovalService.cancelByRequest(context.requestId());
            } catch (RuntimeException error) {
                log.warn("取消待审批命令失败 requestId:{}", context.requestId(), error);
            }
            try {
                chatService.cancel(context);
            } catch (RuntimeException error) {
                log.warn("取消 PaiCLI 请求失败 requestId:{}", context.requestId(), error);
            }
            try {
                shellExecutor.closeRequestShell(context.requestId());
            } catch (RuntimeException error) {
                log.warn("关闭请求 Shell 失败 requestId:{}", context.requestId(), error);
            }
            agentStreamBridge.clear(context.requestId());
        }
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

}
