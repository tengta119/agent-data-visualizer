package top.lbwxxc.ai.domain.agent.service.paicli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.embed.EmbeddedAgent;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliConfigCompiler.AgentDefinition;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Versioned PaiCLI workflow and session runtime used by the HTTP adapter. */
@Service
public final class PaiCliWorkflowRuntime implements IPaiCliWorkflowService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PaiCliConfigCompiler compiler = new PaiCliConfigCompiler();
    private final PaiCliWorkflowEngine engine;
    private final AtomicLong versions = new AtomicLong();
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public PaiCliWorkflowRuntime(PaiCliModelFactory models) {
        this(models, PaiCliToolInstaller.NONE);
    }

    @Autowired
    public PaiCliWorkflowRuntime(PaiCliModelFactory models, PaiCliToolInstaller tools) {
        this.engine = new PaiCliWorkflowEngine(models, ForkJoinPool.commonPool(), tools);
    }

    /** Validates and builds every agent before publishing one new query/execution snapshot. */
    public synchronized long install(AiAgentAutoConfigProperties config) {
        AiAgentAutoConfigProperties copy = copy(config);
        Map<String, AgentDefinition> definitions = compiler.compile(copy);
        String configJson = serialize(copy);
        long version = versions.incrementAndGet();
        current.set(new Snapshot(version, configJson, definitions));
        for (SessionState session : sessions.values()) {
            if (session.version != version && session.lock.tryLock()) {
                try {
                    if (!session.running) {
                        session.closeAgents();
                    }
                } finally {
                    session.lock.unlock();
                }
            }
        }
        return version;
    }

    public AiAgentAutoConfigProperties currentConfiguration() {
        return deserialize(snapshot().configJson());
    }

    public List<AiAgentConfigTableVO.Agent> listAgents() {
        AiAgentAutoConfigProperties config = deserialize(snapshot().configJson());
        List<AiAgentConfigTableVO.Agent> agents = new ArrayList<>();
        for (AiAgentConfigTableVO table : config.getTables().values()) {
            agents.add(table.getAgent());
        }
        return List.copyOf(agents);
    }

    public long version() {
        return snapshot().version();
    }

    public String createSession(String agentId, String userId) {
        requireIdentity(agentId, userId);
        Snapshot snapshot = snapshot();
        if (!snapshot.definitions().containsKey(agentId)) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.AGENT_NOT_FOUND,
                    "Unknown agent ID");
        }
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionState(agentId, userId, snapshot.version()));
        return sessionId;
    }

    public PaiCliWorkflowResult run(String agentId, String userId, String sessionId, String input) {
        return run(agentId, userId, sessionId, input, null);
    }

    /** A locked turn retains the config snapshot it entered with, even if install() publishes a new one. */
    public PaiCliWorkflowResult run(String agentId, String userId, String sessionId, String input,
                                    Consumer<PaiCliWorkflowEvent> listener) {
        return run(agentId, userId, sessionId, input, null, listener);
    }

    public PaiCliWorkflowResult run(String agentId, String userId, String sessionId, String input,
                                    CommandExecutionContext context, Consumer<PaiCliWorkflowEvent> listener) {
        requireIdentity(agentId, userId);
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        SessionState session = session(sessionId);
        if (!session.agentId.equals(agentId) || !session.userId.equals(userId)) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.SESSION_MISMATCH,
                    "Session does not belong to this agent and user");
        }
        session.lock.lock();
        try {
            if (session.running) {
                throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.WORKFLOW_STATE,
                        "A turn is already running in this session");
            }
            Snapshot snapshot = snapshot();
            if (session.version != snapshot.version()) {
                throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.SESSION_EXPIRED,
                        "Session belongs to an older configuration");
            }
            AgentDefinition definition = snapshot.definitions().get(agentId);
            if (definition == null) {
                throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.AGENT_NOT_FOUND,
                        "Unknown agent ID");
            }
            session.running = true;
            session.activeRequestId = context == null ? null : context.requestId();
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.WORKFLOW_STATE,
                            "Request cancelled before execution");
                }
                return engine.run(definition, input, session.stageAgents, session.activeAgents, context, listener);
            } finally {
                session.running = false;
                session.activeRequestId = null;
                Snapshot latest = current.get();
                if (latest != null && session.version != latest.version()) {
                    session.closeAgents();
                }
            }
        } finally {
            session.lock.unlock();
        }
    }

    /** Best-effort cancellation of the active turn, including each in-flight model call. */
    public void cancel(String agentId, String userId, String sessionId) {
        requireIdentity(agentId, userId);
        SessionState session = session(sessionId);
        if (!session.agentId.equals(agentId) || !session.userId.equals(userId)) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.SESSION_MISMATCH,
                    "Session does not belong to this agent and user");
        }
        session.activeAgents.forEach(EmbeddedAgent::cancel);
    }

    public void cancelRequest(CommandExecutionContext context) {
        if (context == null || context.requestId() == null || context.sessionId() == null) {
            return;
        }
        SessionState state = sessions.get(context.sessionId());
        if (state != null && state.agentId.equals(context.agentId())
                && state.userId.equals(context.userId())
                && context.requestId().equals(state.activeRequestId)) {
            state.activeAgents.forEach(EmbeddedAgent::cancel);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (SessionState session : sessions.values()) {
            session.activeAgents.forEach(EmbeddedAgent::cancel);
            if (session.lock.tryLock()) {
                try {
                    session.closeAgents();
                } finally {
                    session.lock.unlock();
                }
            }
        }
    }

    private SessionState session(String sessionId) {
        SessionState session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.SESSION_NOT_FOUND,
                    "Unknown session ID");
        }
        return session;
    }

    private void requireIdentity(String agentId, String userId) {
        if (agentId == null || agentId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("agentId and userId must not be blank");
        }
    }

    private Snapshot snapshot() {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.NOT_CONFIGURED,
                    "PaiCLI workflow is not configured");
        }
        return snapshot;
    }

    private AiAgentAutoConfigProperties copy(AiAgentAutoConfigProperties config) {
        if (config == null) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                    "Agent configuration is missing");
        }
        return deserialize(serialize(config));
    }

    private String serialize(AiAgentAutoConfigProperties config) {
        try {
            return mapper.writeValueAsString(config);
        } catch (JsonProcessingException error) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                    "Agent configuration cannot be serialized");
        }
    }

    private AiAgentAutoConfigProperties deserialize(String json) {
        try {
            return mapper.readValue(json, AiAgentAutoConfigProperties.class);
        } catch (JsonProcessingException error) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                    "Agent configuration cannot be read");
        }
    }

    private record Snapshot(long version, String configJson, Map<String, AgentDefinition> definitions) {
    }

    private static final class SessionState {
        private final String agentId;
        private final String userId;
        private final long version;
        private final ReentrantLock lock = new ReentrantLock(true);
        private boolean running;
        private volatile String activeRequestId;
        private final Map<String, EmbeddedAgent> stageAgents = new ConcurrentHashMap<>();
        private final Set<EmbeddedAgent> activeAgents = ConcurrentHashMap.newKeySet();

        private SessionState(String agentId, String userId, long version) {
            this.agentId = agentId;
            this.userId = userId;
            this.version = version;
        }

        private void closeAgents() {
            stageAgents.values().forEach(EmbeddedAgent::close);
            stageAgents.clear();
        }
    }
}
