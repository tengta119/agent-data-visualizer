package top.lbwxxc.ai.domain.agent.service.chat;

import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.service.IChatService;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.paicli.IPaiCliWorkflowService;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowEvent;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowResult;

import java.util.List;
import java.util.function.Consumer;

@Service
public final class PaiCliChatService implements IChatService {
    private final IPaiCliWorkflowService workflow;

    public PaiCliChatService(IPaiCliWorkflowService workflow) {
        this.workflow = workflow;
    }

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        return workflow.listAgents();
    }

    @Override
    public String createSession(String agentId, String userId) {
        return workflow.createSession(agentId, userId);
    }

    @Override
    public PaiCliWorkflowResult handleMessage(String agentId, String userId, String sessionId, String message) {
        return workflow.run(agentId, userId, sessionId, message);
    }

    @Override
    public PaiCliWorkflowResult handleMessageStream(String agentId, String userId, String sessionId,
                                                    CommandExecutionContext context, String message,
                                                    Consumer<PaiCliWorkflowEvent> listener) {
        return workflow.run(agentId, userId, sessionId, message, context, listener);
    }

    @Override
    public void cancel(CommandExecutionContext context) {
        workflow.cancelRequest(context);
    }
}
