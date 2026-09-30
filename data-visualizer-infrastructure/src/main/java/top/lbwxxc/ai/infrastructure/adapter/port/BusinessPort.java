package top.lbwxxc.ai.infrastructure.adapter.port;


import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.adapter.port.IBusinessPort;
import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;
import top.lbwxxc.ai.infrastructure.socket.NettySocketServer;

import jakarta.annotation.Resource;

/**
 * 业务端口
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/2/24 08:11
 */
@Service
public class BusinessPort implements IBusinessPort {

    @Resource
    private NettySocketServer nettySocketServer;

    @Override
    public GatewayResponseVO action(GatewayCommandEntity commandEntity) {

        return nettySocketServer.sendCommand(commandEntity);
    }

    @Override
    public GatewayResponseVO queryClients() {
        return nettySocketServer.queryClients();
    }


}
