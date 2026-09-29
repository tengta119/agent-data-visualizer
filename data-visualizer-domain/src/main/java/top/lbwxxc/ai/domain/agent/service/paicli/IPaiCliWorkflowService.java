package top.lbwxxc.ai.domain.agent.service.paicli;

import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;

import java.util.List;
import java.util.function.Consumer;

/** Framework-neutral boundary for the HTTP adapter added in TASK-008. */
public interface IPaiCliWorkflowService {
    long install(AiAgentAutoConfigProperties config);

    AiAgentAutoConfigProperties currentConfiguration();

    List<AiAgentConfigTableVO.Agent> listAgents();

    String createSession(String agentId, String userId);

    PaiCliWorkflowResult run(String agentId, String userId, String sessionId, String input);

    PaiCliWorkflowResult run(String agentId, String userId, String sessionId, String input,
                             Consumer<PaiCliWorkflowEvent> listener);

    void cancel(String agentId, String userId, String sessionId);
}
