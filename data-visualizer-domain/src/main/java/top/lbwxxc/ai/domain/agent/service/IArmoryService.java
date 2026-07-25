package top.lbwxxc.ai.domain.agent.service;

import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;

import java.util.List;

/**
 * 装配接口
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/17 08:13
 */
public interface IArmoryService {

    void acceptArmoryAgents(AiAgentAutoConfigProperties aiAgentAutoConfigProperties) throws Exception;

    AiAgentAutoConfigProperties queryCurrentAiAgentConfigTableVO();

}
