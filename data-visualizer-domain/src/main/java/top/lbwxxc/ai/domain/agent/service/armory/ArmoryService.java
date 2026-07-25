package top.lbwxxc.ai.domain.agent.service.armory;

import top.lbwxxc.ai.domain.agent.model.entity.ArmoryCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import top.lbwxxc.ai.domain.agent.service.IArmoryService;
import top.lbwxxc.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

@Slf4j
@Service
public class ArmoryService implements IArmoryService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    // 当前的 Agent 配置文件
    private static AiAgentAutoConfigProperties currentAiAgentAutoConfigProperties;

    @Override
    public void acceptArmoryAgents(AiAgentAutoConfigProperties aiAgentAutoConfigProperties) throws Exception {
        currentAiAgentAutoConfigProperties = aiAgentAutoConfigProperties;
        for (AiAgentConfigTableVO table : currentAiAgentAutoConfigProperties.getTables().values()) {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> handler = defaultArmoryFactory.armoryStrategyHandler();
            handler.apply(
                    ArmoryCommandEntity.builder()
                            .aiAgentConfigTableVO(table)
                            .build(),
                    new DefaultArmoryFactory.DynamicContext());
        }
    }

    @Override
    public AiAgentAutoConfigProperties queryCurrentAiAgentConfigTableVO() {
        return currentAiAgentAutoConfigProperties;
    }


}
