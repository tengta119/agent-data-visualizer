package top.lbwxxc.ai.trigger.http;

import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import top.lbwxxc.ai.api.IAgentAdminService;
import top.lbwxxc.ai.api.dto.UpdateAgentConfigRequestDTO;
import top.lbwxxc.ai.api.response.QueryCurrentAgentConfigResponse;
import top.lbwxxc.ai.api.response.Response;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import top.lbwxxc.ai.domain.agent.service.IArmoryService;
import top.lbwxxc.ai.types.enums.ResponseCode;
import top.lbwxxc.ai.types.exception.AppException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@CrossOrigin(origins = "*")
public class AgentAdminService implements IAgentAdminService {

    @Resource
    private IArmoryService armoryService;

    @RequestMapping(value = "query_current_agent_config", method = RequestMethod.GET)
    @Override
    public Response<QueryCurrentAgentConfigResponse> queryCurrentAgentConfig() {
        try {
            log.info("查询当前生效的 agent 配置");
            return Response.<QueryCurrentAgentConfigResponse>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(buildCurrentConfigResponse(armoryService.queryCurrentAiAgentConfigTableVO()))
                    .build();
        } catch (AppException e) {
            log.error("查询当前生效的 agent 配置异常", e);
            return Response.<QueryCurrentAgentConfigResponse>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询当前生效的 agent 配置失败", e);
            return Response.<QueryCurrentAgentConfigResponse>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "update_agent_config", method = RequestMethod.POST)
    @Override
    public Response<QueryCurrentAgentConfigResponse> updateAgentConfig(@RequestBody UpdateAgentConfigRequestDTO requestDTO) {
        try {
            log.info("动态更新 agent 配置");

            AiAgentAutoConfigProperties aiAgentAutoConfigProperties = JSON.parseObject(
                    JSON.toJSONString(requestDTO),
                    AiAgentAutoConfigProperties.class
            );
            armoryService.acceptArmoryAgents(aiAgentAutoConfigProperties);

            return Response.<QueryCurrentAgentConfigResponse>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(buildCurrentConfigResponse(armoryService.queryCurrentAiAgentConfigTableVO()))
                    .build();
        } catch (AppException e) {
            log.error("动态更新 agent 配置异常", e);
            return Response.<QueryCurrentAgentConfigResponse>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("动态更新 agent 配置失败", e);
            return Response.<QueryCurrentAgentConfigResponse>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private QueryCurrentAgentConfigResponse buildCurrentConfigResponse(AiAgentAutoConfigProperties currentConfig) {
        QueryCurrentAgentConfigResponse responseDTO = new QueryCurrentAgentConfigResponse();
        responseDTO.setEnabled(currentConfig != null && currentConfig.isEnabled());

        Map<String, Object> tables = new LinkedHashMap<>();
        if (currentConfig != null && currentConfig.getTables() != null) {
            tables.putAll(currentConfig.getTables());
        }
        responseDTO.setTables(tables);
        return responseDTO;
    }

}
