package top.lbwxxc.ai.domain.agent.service.chat;

import com.google.adk.plugins.BasePlugin;
import top.lbwxxc.ai.domain.agent.model.entity.ChatCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import top.lbwxxc.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import top.lbwxxc.ai.domain.agent.service.armory.matter.plugin.ContextCompactionPlugin;
import top.lbwxxc.ai.domain.agent.service.armory.matter.plugin.MyLogPlugin;
import top.lbwxxc.ai.domain.agent.service.chat.stream.AgentStreamBridge;
import top.lbwxxc.ai.types.enums.ResponseCode;
import top.lbwxxc.ai.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private AgentStreamBridge agentStreamBridge;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    private String buildSessionCacheKey(String agentId, String userId) {
        return agentId + ":" + userId;
    }

    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        String cacheKey = buildSessionCacheKey(agentId, userId);

        return userSessions.computeIfAbsent(cacheKey, key -> {
            Session session = runner.sessionService().createSession(appName, userId)
                    .blockingGet();
            return session.id();
        });
    }

    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String requestId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }
        Runner runner = createRequestRunner(aiAgentRegisterVO, requestId);

        Content userMsg = Content.fromParts(Part.fromText(message));
        return runner.runAsync(userId, sessionId, userMsg);
    }

    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    private Runner createRequestRunner(AiAgentRegisterVO registerVO, String requestId) {
        Runner baseRunner = registerVO.getRunner();
        List<BasePlugin> sourcePlugins = registerVO.getPlugins();
        List<BasePlugin> plugins = sourcePlugins == null ? new ArrayList<>() : new ArrayList<>(sourcePlugins);
        boolean replacedLogPlugin = false;

        for (int i = 0; i < plugins.size(); i++) {
            BasePlugin basePlugin = plugins.get(i);
            if (basePlugin.getName().equals("MyLogPlugin")) {
                // 不能直接改 registerVO 里的插件列表，否则并发请求会共享同一个上下文插件实例。
                plugins.set(i, new MyLogPlugin(requestId, agentStreamBridge));
                replacedLogPlugin = true;
            }
            if (basePlugin.getName().equals("ContextCompactionPlugin")) {
                plugins.set(i, new ContextCompactionPlugin(requestId, agentStreamBridge));
            }
        }

        if (!replacedLogPlugin) {
            plugins.add(new MyLogPlugin(requestId, agentStreamBridge));
        }

        return Runner.builder()
                .agent(baseRunner.agent())
                .appName(baseRunner.appName())
                .artifactService(baseRunner.artifactService())
                .sessionService(baseRunner.sessionService())
                .memoryService(baseRunner.memoryService())
                .plugins(plugins)
                .build();
    }

}
