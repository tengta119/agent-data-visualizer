package top.lbwxxc.ai.domain.agent.service.paicli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.embed.EmbeddedAgent;
import com.paicli.embed.ToolInvocationContext;
import com.paicli.mcp.McpClient;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.transport.StdioTransport;
import com.paicli.runtime.CancellationContext;
import com.paicli.tool.ToolOutput;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContextHolder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandSensitiveRedactor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
public final class DefaultPaiCliToolInstaller implements PaiCliToolInstaller {
    private final ShellExecutor shellExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultPaiCliToolInstaller(ShellExecutor shellExecutor) {
        this.shellExecutor = shellExecutor;
    }

    @Override
    public void install(EmbeddedAgent agent, List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> tools) {
        for (var config : tools) {
            if (config.getLocal() != null) {
                if (!"ShellExecutorToolCallbackProvider".equals(config.getLocal().getName())) {
                    throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                            "Unsupported local tool");
                }
                installShell(agent);
            } else if (config.getStdio() != null) {
                installStdio(agent, config.getStdio());
            } else {
                throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                        "Unsupported MCP transport");
            }
        }
    }

    private void installShell(EmbeddedAgent agent) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("command").put("type", "string");
        properties.putObject("commandType").put("type", "string");
        properties.putObject("hostName").put("type", "string");
        schema.putArray("required").add("command").add("commandType");
        agent.registerMcpToolOutput(new McpToolDescriptor("ShellExecutor", "execute",
                McpToolDescriptor.namespaced("ShellExecutor", "execute"),
                "Execute a local or remote command through the configured command policy and one-time approval.",
                schema), arguments -> {
            if (CancellationContext.isCancelled()) {
                return ToolOutput.failure("Command not executed: request cancelled");
            }
            try {
                ShellExecutor.CommandRequest request = mapper.readValue(arguments, ShellExecutor.CommandRequest.class);
                Object current = ToolInvocationContext.current();
                CommandExecutionContext context = current instanceof CommandExecutionContext scoped ? scoped : null;
                if (context != null) {
                    CommandExecutionContextHolder.set(context);
                }
                try {
                    ShellExecutor.CommandResponse response = shellExecutor.execute(request);
                    ShellExecutor.CommandResponse safe = new ShellExecutor.CommandResponse(
                            response.getTargetIp(), CommandSensitiveRedactor.redact(response.getCommand()),
                            response.getResponseStatus(),
                            CommandSensitiveRedactor.redact(response.getResponseMessage()));
                    String json = mapper.writeValueAsString(safe);
                    return "success".equals(response.getResponseStatus())
                            ? ToolOutput.text(json) : ToolOutput.failure(json);
                } finally {
                    CommandExecutionContextHolder.clear();
                }
            } catch (IOException error) {
                throw new IllegalArgumentException("Invalid command tool arguments", error);
            }
        });
    }

    private void installStdio(EmbeddedAgent agent,
                              AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters config) {
        var parameters = config.getServerParameters();
        McpClient client = null;
        try {
            client = new McpClient(config.getName(), new StdioTransport(parameters.getCommand(),
                    parameters.getArgs(), parameters.getEnv(), Path.of(System.getProperty("user.dir"))));
            client.initialize();
            List<McpToolDescriptor> descriptors = client.listTools();
            if (descriptors.isEmpty()) {
                throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                        "stdio MCP server exposes no tools: " + config.getName());
            }
            McpClient connected = client;
            for (McpToolDescriptor descriptor : descriptors) {
                agent.registerMcpToolOutput(descriptor, arguments -> {
                    if (CancellationContext.isCancelled()) {
                        return ToolOutput.failure("MCP call not executed: request cancelled");
                    }
                    try {
                        return connected.callToolOutput(descriptor.name(), arguments);
                    } catch (IOException error) {
                        throw new IllegalStateException("MCP tool call failed", error);
                    }
                });
            }
            agent.onClose(connected::close);
        } catch (IOException | RuntimeException error) {
            if (client != null) {
                client.close();
            }
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                    "Cannot connect stdio MCP server: " + config.getName());
        }
    }
}
