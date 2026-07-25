package top.lbwxxc.ai.api.response;

import lombok.Data;

import java.util.Map;

/**
 * 当前生效的 Agent 配置
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/7/25 18:20
 */
@Data
public class QueryCurrentAgentConfigResponse {

    /**
     * 是否启用自动装配
     */
    private Boolean enabled;

    /**
     * 当前生效的配置表
     */
    private Map<String, Object> tables;

}
