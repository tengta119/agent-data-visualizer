package top.lbwxxc.ai.test.paicli;

import com.paicli.embed.EmbeddedAgent;
import com.paicli.embed.EmbeddedTurnException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class PaiCliShellToolTest {
    @Test
    void shellSchemaDescribesTargetInsteadOfShellDialect() {
        try (EmbeddedAgent agent = shellAgent(mock(ShellExecutor.class), mock(LlmClient.class))) {
            var definition = agent.availableTools().get(0);
            var properties = definition.parameters().path("properties");
            assertEquals("[\"local\",\"remote\"]", properties.path("commandType").path("enum").toString());
            assertTrue(properties.path("commandType").path("description").asText().contains("NOT shell dialect"));
            assertTrue(properties.path("hostName").path("description").asText().contains("empty string"));
        }
    }

    @Test
    void invalidTargetIsExplainedWithoutExecutingOrLeakingArguments() {
        ShellExecutor shell = mock(ShellExecutor.class);
        try (EmbeddedAgent agent = shellAgent(shell, mock(LlmClient.class))) {
            for (String target : List.of("shell", "cmd", "bash", "powershell")) {
                var output = agent.invokeTool("mcp__ShellExecutor__execute",
                        "{\"command\":\"echo --token private-value\",\"commandType\":\"" + target + "\"}");
                assertFalse(output.successful());
                assertTrue(output.text().contains("commandType must be local or remote"));
                assertFalse(output.text().contains("private-value"));
            }
            verify(shell, never()).execute(any());
            assertNull(CommandExecutionContextHolder.get());
        }
    }

    @Test
    void modelGeneratedInvalidTargetPreservesDiagnosticThroughEmbeddedRun() throws Exception {
        ShellExecutor shell = mock(ShellExecutor.class);
        LlmClient model = mock(LlmClient.class);
        when(model.supportsTools()).thenReturn(true);
        when(model.chat(any(), any(), any())).thenReturn(new LlmClient.ChatResponse("assistant", "",
                List.of(new LlmClient.ToolCall("call-1", new LlmClient.ToolCall.Function(
                        "mcp__ShellExecutor__execute", "{\"command\":\"systeminfo\",\"commandType\":\"shell\"}"))), 1, 1));
        try (EmbeddedAgent agent = shellAgent(shell, model)) {
            EmbeddedTurnException error = assertThrows(EmbeddedTurnException.class,
                    () -> agent.run("查询本地电脑硬件配置并画图"));
            assertEquals(EmbeddedTurnException.Kind.TOOL, error.kind());
            assertTrue(error.getMessage().contains("mcp__ShellExecutor__execute"));
            assertTrue(error.getMessage().contains("commandType must be local or remote"));
            verify(shell, never()).execute(any());
        }
    }

    @Test
    void validLocalTargetExecutesAndCleansRequestContext() throws Exception {
        ShellExecutor shell = mock(ShellExecutor.class);
        when(shell.execute(any())).thenAnswer(invocation -> {
            ShellExecutor.CommandRequest request = invocation.getArgument(0);
            assertEquals(ShellExecutor.CommandTypeEnum.local, request.getCommandType());
            assertEquals("request-1", CommandExecutionContextHolder.get().requestId());
            return new ShellExecutor.CommandResponse("Local", "pwd", "success", "workspace");
        });
        try (EmbeddedAgent agent = shellAgent(shell, mock(LlmClient.class))) {
            var output = ToolInvocationContext.call(new CommandExecutionContext("request-1", "agent", "user", "session"),
                    () -> agent.invokeTool("mcp__ShellExecutor__execute", "{\"command\":\"pwd\",\"commandType\":\"local\"}"));
            assertTrue(output.successful());
            verify(shell).execute(any());
            assertNull(CommandExecutionContextHolder.get());
        }
    }

    private EmbeddedAgent shellAgent(ShellExecutor shell, LlmClient model) {
        EmbeddedAgent agent = new EmbeddedAgent(model, "test instruction");
        var local = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters();
        local.setName("ShellExecutor");
        var tool = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        tool.setLocal(local);
        new DefaultPaiCliToolInstaller(shell).install(agent, List.of(tool));
        return agent;
    }

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
        local.setName("ShellExecutor");
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
