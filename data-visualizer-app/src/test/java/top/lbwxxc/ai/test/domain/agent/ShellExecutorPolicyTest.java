package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.adapter.port.IBusinessPort;
import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandAuditRecorder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandExecutionPolicyProperties;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReviewer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellExecutorPolicyTest {

    @Test
    void forbiddenRemoteCommandIsNotForwarded() {
        AtomicInteger calls = new AtomicInteger();
        IBusinessPort businessPort = new IBusinessPort() {
            @Override
            public GatewayResponseVO action(GatewayCommandEntity commandEntity) {
                calls.incrementAndGet();
                return GatewayResponseVO.builder().status("success").message("unexpected").build();
            }

            @Override
            public GatewayResponseVO queryClients() {
                calls.incrementAndGet();
                return GatewayResponseVO.builder().status("success").message("unexpected").build();
            }
        };
        CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
        ShellExecutor executor = new ShellExecutor(
                businessPort,
                new CommandPolicyReviewer(properties),
                new CommandAuditRecorder(),
                properties
        );

        ShellExecutor.CommandResponse response = executor.execute(new ShellExecutor.CommandRequest(
                "pwd", ShellExecutor.CommandTypeEnum.remote, "unconfigured-client"
        ));

        assertEquals("forbidden", response.getResponseStatus());
        assertEquals(0, calls.get());
    }
}
