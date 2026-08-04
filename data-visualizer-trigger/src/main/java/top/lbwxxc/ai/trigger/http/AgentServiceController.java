package top.lbwxxc.ai.trigger.http;

import com.alibaba.fastjson.JSON;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import top.lbwxxc.ai.api.IAgentService;
import top.lbwxxc.ai.api.dto.*;
import top.lbwxxc.ai.api.response.Response;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.service.IChatService;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamResponseDTO;
import top.lbwxxc.ai.types.enums.ResponseCode;
import top.lbwxxc.ai.types.exception.AppException;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    private static final Pattern DRAWIO_XML_PATTERN = Pattern.compile("(?s)(<mxfile[\\s\\S]*?</mxfile>|<mxGraphModel[\\s\\S]*?</mxGraphModel>)");

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

            List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());
            String result = messages.stream().reduce((first, second) -> second).orElse("");
            ChatResponseDTO responseDTO = parseChatResponse(result, String.join("\n", messages));

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

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);
        AtomicReference<String> requestIdRef = new AtomicReference<>();
        AtomicReference<Disposable> streamDisposableRef = new AtomicReference<>();
        AtomicReference<String> finalResultRef = new AtomicReference<>("");
        AtomicBoolean cleaned = new AtomicBoolean(false);

        try {
                String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
                requestDTO.setSessionId(sessionId);

            log.info("流式对话 agentId:{} userId:{} sessionId:{} message:{}", requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            final String currentSessionId = sessionId;
            String requestId = UUID.randomUUID().toString();
            requestIdRef.set(requestId);
            final String currentRequestId = requestId;
            agentStreamBridge.register(currentSessionId, currentRequestId, emitter);

            CompletableFuture.runAsync(() -> {
                Disposable streamDisposable = chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(), currentSessionId, currentRequestId, requestDTO.getMessage())
                        .subscribe(
                                event -> {
                                    String content = event.stringifyContent();
                                    if (StringUtils.isNotBlank(content)) {
                                        finalResultRef.set(content);
                                    }
                                },
                                throwable -> {
                                    log.error("流式对话失败 sessionId:{} requestId:{}", currentSessionId, currentRequestId, throwable);
                                    sendStreamError(currentRequestId, throwable);
                                    cleanupStream(cleaned, currentRequestId, streamDisposableRef.get());
                                    emitter.completeWithError(throwable);
                                },
                                () -> {
                                    try {
                                        ChatResponseDTO responseDTO = parseChatResponse(finalResultRef.get(), finalResultRef.get());
                                        AgentStreamResponseDTO agentStreamResponseDTO = AgentStreamResponseDTO.builder()
                                                .type("result")
                                                .stage(StringUtils.defaultIfBlank(responseDTO.getType(), "user"))
                                                .sessionId(currentSessionId)
                                                .requestId(currentRequestId)
                                                .content(responseDTO.getContent())
                                                .timestamp(System.currentTimeMillis())
                                                .build();

                                        agentStreamBridge.publish(agentStreamResponseDTO);
                                        agentStreamBridge.publishDone(currentSessionId, currentRequestId, "completed");
                                        emitter.complete();

                                        log.info("Agent 最终输出结果 {}", agentStreamResponseDTO);
                                    } catch (Exception e) {
                                        log.error("流式结果组装失败 sessionId:{} requestId:{}", currentSessionId, currentRequestId, e);
                                        sendStreamError(currentRequestId, e);
                                        emitter.completeWithError(e);
                                    } finally {
                                        cleanupStream(cleaned, currentRequestId, streamDisposableRef.get());
                                    }
                                }
                        );
                streamDisposableRef.set(streamDisposable);
            });
        } catch (Exception e) {
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }

        emitter.onCompletion(() -> cleanupStream(cleaned, requestIdRef.get(), streamDisposableRef.get()));
        emitter.onTimeout(() -> cleanupStream(cleaned, requestIdRef.get(), streamDisposableRef.get()));
        emitter.onError(error -> cleanupStream(cleaned, requestIdRef.get(), streamDisposableRef.get()));

        return emitter;
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

        List<ChatResponseDTO> responses = extractJsonResponses(raw);
        for (int index = responses.size() - 1; index >= 0; index--) {
            ChatResponseDTO response = responses.get(index);
            String type = StringUtils.defaultString(response.getType()).trim().toLowerCase();

            if ("user".equals(type)) {
                return buildChatResponse("user", response.getContent());
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

    private List<ChatResponseDTO> extractJsonResponses(String raw) {
        List<ChatResponseDTO> responses = new ArrayList<>();
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
                    responses.add(response);
                }
                index += candidate.length() - 1;
            } catch (Exception e) {
                log.debug("跳过无效 JSON 片段: {}", e.getMessage());
            }
        }

        return responses;
    }

    private boolean isSupportedResponseType(String type) {
        String normalizedType = StringUtils.defaultString(type).trim().toLowerCase();
        return "user".equals(normalizedType)
                || "drawio".equals(normalizedType)
                || "drawio_node".equals(normalizedType)
                || "drawio_edge".equals(normalizedType)
                || "drawio_done".equals(normalizedType);
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

    private void cleanupStream(AtomicBoolean cleaned, String requestId, Disposable streamDisposable) {
        if (!cleaned.compareAndSet(false, true)) {
            return;
        }

        if (streamDisposable != null && !streamDisposable.isDisposed()) {
            streamDisposable.dispose();
        }
        agentStreamBridge.clear(requestId);
    }

}
