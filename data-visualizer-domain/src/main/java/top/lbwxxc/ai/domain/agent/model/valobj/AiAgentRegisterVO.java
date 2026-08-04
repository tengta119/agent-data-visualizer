package top.lbwxxc.ai.domain.agent.model.valobj;

import com.google.adk.agents.BaseAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.InMemoryRunner;
import lombok.*;

import java.util.List;

/**
 * Ai Agent 智能体注册值对象
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/17 08:19
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class AiAgentRegisterVO {

    /**
     * 智能体名称
     */
    private String appName;

    /**
     * 智能体ID
     */
    private String agentId;

    BaseAgent baseAgent;

    List<BasePlugin> plugins;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /**
     * 智能体执行对象
     */
    private InMemoryRunner runner;

}
