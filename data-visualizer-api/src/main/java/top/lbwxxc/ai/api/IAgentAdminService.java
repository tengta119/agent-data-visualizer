package top.lbwxxc.ai.api;

import top.lbwxxc.ai.api.dto.UpdateAgentConfigRequestDTO;
import top.lbwxxc.ai.api.response.QueryCurrentAgentConfigResponse;
import top.lbwxxc.ai.api.response.Response;

public interface IAgentAdminService {

    Response<QueryCurrentAgentConfigResponse> queryCurrentAgentConfig();

    Response<QueryCurrentAgentConfigResponse> updateAgentConfig(UpdateAgentConfigRequestDTO requestDTO);

}
