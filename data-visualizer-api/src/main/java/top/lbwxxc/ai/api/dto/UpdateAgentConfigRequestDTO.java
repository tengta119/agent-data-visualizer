package top.lbwxxc.ai.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 动态更新 Agent 配置请求对象
 */
@Data
public class UpdateAgentConfigRequestDTO {

    /**
     * 是否启用自动装配
     */
    private Boolean enabled;

    /**
     * Agent 配置表
     */
    private Map<String, Object> tables;

}
