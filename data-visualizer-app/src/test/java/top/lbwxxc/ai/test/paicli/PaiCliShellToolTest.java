package top.lbwxxc.ai.test.paicli;

import com.paicli.embed.EmbeddedAgent;
import com.paicli.embed.ToolInvocationContext;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContextHolder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.paicli.DefaultPaiCliToolInstaller;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaiCliShellToolTest {
    @Test
    void yamlWhitelistAndRequestContextBoundOnlyAtShellEntry() throws Exception {
        ShellExecutor shell = mock(ShellExecutor.class);
        AtomicReference<CommandExecutionContext> seen = new AtomicReference<>();
        when(shell.execute(any())).thenAnswer(invocation -> {
            seen.set(CommandExecutionContextHolder.get());
            return new ShellExecutor.CommandResponse("Local", "pwd", "forbidden", "blocked");
        });
        LlmClient model = mock(LlmClient.class);
        EmbeddedAgent agent = new EmbeddedAgent(model, "test instruction");
        var local = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters();
        local.setName("ShellExecutorToolCallbackProvider");
        var tool = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        tool.setLocal(local);
        new DefaultPaiCliToolInstaller(shell).install(agent, List.of(tool));

        assertEquals(List.of("mcp__ShellExecutor__execute"),
                agent.availableTools().stream().map(LlmClient.Tool::name).toList());
        assertFalse(agent.invokeTool("execute_command", "{}").successful());

        CommandExecutionContext context = new CommandExecutionContext("request-1", "agent", "user", "session");
        var output = ToolInvocationContext.call(context,
                () -> agent.invokeTool("mcp__ShellExecutor__execute",
                        "{\"command\":\"pwd\",\"commandType\":\"local\",\"hostName\":\"\"}"));
        assertFalse(output.successful());
        assertTrue(output.text().contains("forbidden"));
        assertEquals(context, seen.get());
        assertNull(CommandExecutionContextHolder.get());
        agent.close();
    }
}
