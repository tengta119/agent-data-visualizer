package top.lbwxxc.ai.domain.agent.service;

import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowEvent;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * 对话接口
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/17 08:13
 */
public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    PaiCliWorkflowResult handleMessage(String agentId, String userId, String sessionId, String message);

    PaiCliWorkflowResult handleMessageStream(String agentId, String userId, String sessionId,
                                             CommandExecutionContext context, String message,
                                             Consumer<PaiCliWorkflowEvent> listener);

    void cancel(CommandExecutionContext context);

}
