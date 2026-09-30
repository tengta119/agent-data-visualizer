package top.lbwxxc.ai.domain.agent.service.paicli;

import com.paicli.embed.EmbeddedAgent;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/** Installs only tools explicitly authorized by the active agent YAML. */
@FunctionalInterface
public interface PaiCliToolInstaller {
    void install(EmbeddedAgent agent, List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> tools);

    PaiCliToolInstaller NONE = (agent, tools) -> {
        if (!tools.isEmpty()) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                    "No PaiCLI tool installer is available");
        }
    };
}
