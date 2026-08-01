package top.lbwxxc.ai.test.api.tool;


import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.ShellExecutor;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class MCPTest {

    @Resource
    private ShellExecutor shellExecutor;

    @Test
    public void test_Command() {
        List<String> command = new ArrayList<>();
        command.add("send-command --ip 127.0.0.1 --command \"ls\"");
        command.add("clients");
        ShellExecutor.CommandRequest request = new ShellExecutor.CommandRequest();
        request.setCommand("ls");
        request.setCommandType(ShellExecutor.CommandTypeEnum.remote);
        request.setHostName("127.0.0.1");

        for (int i = 0; i < command.size(); i++) {
            ShellExecutor.CommandResponse execute = shellExecutor.execute(request);

            log.info("执行结果 {}", execute);
        }
    }
}
