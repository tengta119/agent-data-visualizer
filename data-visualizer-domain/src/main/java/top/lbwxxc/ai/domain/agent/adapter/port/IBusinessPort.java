package top.lbwxxc.ai.domain.agent.adapter.port;


import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;

/**
 * 业务端口
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/2/24 07:54
 */
public interface IBusinessPort {

    GatewayResponseVO action(GatewayCommandEntity commandEntity);

    GatewayResponseVO queryClients();
}
