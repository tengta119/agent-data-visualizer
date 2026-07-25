package top.lbwxxc.ai.domain.agent.service.armory.matter.skills;


import org.springframework.ai.tool.ToolCallback;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

/**
 * 工具 skills 构建服务
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/2/6 08:03
 */
public interface ToolSkillsCreateService {

    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception;

}
