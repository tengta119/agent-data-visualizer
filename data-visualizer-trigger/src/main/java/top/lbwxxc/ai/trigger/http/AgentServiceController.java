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

    private static final Pattern JSON_PATTERN = Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

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
        ChatResponseDTO responseDTO = new ChatResponseDTO();

        try {
            Matcher matcher = JSON_PATTERN.matcher(StringUtils.defaultString(result));
            if (!matcher.find()) {
                throw new IllegalArgumentException("未找到 JSON 数据");
            }

            String json = matcher.group(1);
            ChatResponseDTO parsed = JSON.parseObject(json, ChatResponseDTO.class);

            if (parsed != null) {
                responseDTO = parsed;
                if (responseDTO.getType() == null) {
                    responseDTO.setType("user");
                }
            } else {
                responseDTO.setType("user");
                responseDTO.setContent(fallbackContent);
            }
        } catch (Exception e) {
            responseDTO.setType("user");
            responseDTO.setContent(fallbackContent);
            log.info("反序列化出现错误 {}", e.getMessage());
        }

        return responseDTO;
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
