package top.lbwxxc.ai;

import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.ShellExecutor ;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@Configurable
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class);
    }

    @Bean("ShellExecutorToolCallbackProvider")
    public ToolCallbackProvider testTools(ShellExecutor shellExecutor) {
        return MethodToolCallbackProvider.builder().toolObjects(shellExecutor).build();
    }

}
