package top.lbwxxc.ai.domain.agent.service.paicli;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** 仅安装当前 Agent 配置显式声明的工具；不启用 PaiCLI 的默认工具集。 */
    @Override
    public void install(EmbeddedAgent agent, List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> tools) {
        for (var config : tools) {
            if (config.getLocal() != null) {
                if (!"ShellExecutor".equals(config.getLocal().getName())
                        && !"ShellExecutorToolCallbackProvider".equals(config.getLocal().getName())) {
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

    /**
     * 只向 Agent 暴露经过现有策略与审批链的 Shell 工具。
     * 请求上下文在工具调用线程临时绑定，并在 finally 中清除，防止线程复用时串请求。
     */
    private void installShell(EmbeddedAgent agent) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("command").put("type", "string").put("minLength", 1)
                .put("description", "Command text, subject to the server command policy and one-time approval. Complex shell syntax may be rejected depending on policy. Never invoke rm.");
        ObjectNode commandType = properties.putObject("commandType");
        commandType.put("type", "string");
        commandType.putArray("enum").add("local").add("remote");
        commandType.put("description", "Execution target, NOT shell dialect: local = backend machine; remote = connected approved host. Never use cmd, shell, bash or powershell here.");
        properties.putObject("hostName").put("type", "string")
                .put("description", "For local, omit or use an empty string; do not use localhost or local. For remote, use a host in the server allowlist.");
        schema.putArray("required").add("command").add("commandType");
        agent.registerMcpToolOutput(new McpToolDescriptor("ShellExecutor", "execute",
                McpToolDescriptor.namespaced("ShellExecutor", "execute"),
                "Execute a local or remote command through the configured command policy and one-time approval. "
                        + "The backend OS is " + System.getProperty("os.name")
                        + ". Local execution prefers PowerShell (pwsh), then bash/sh if unavailable. "
                        + "commandType selects the target, not the shell. Prefer simple commands; do not assume Windows cmd or Linux syntax.",
                schema), arguments -> {
            if (CancellationContext.isCancelled()) {
                return ToolOutput.failure("Command not executed: request cancelled");
            }
            try {
                JsonNode args = mapper.readTree(arguments);
                if (args == null || !args.isObject()) {
                    return ToolOutput.failure("Invalid command tool arguments: expected a JSON object");
                }
                if (!args.path("command").isTextual() || args.path("command").asText().isBlank()) {
                    return ToolOutput.failure("Invalid command tool arguments: command must be a non-empty string");
                }
                String target = args.path("commandType").asText();
                if (!args.path("commandType").isTextual()
                        || (!"local".equals(target) && !"remote".equals(target))) {
                    return ToolOutput.failure("Invalid command tool arguments: commandType must be local or remote (execution target, not cmd/shell/bash/powershell)");
                }
                ShellExecutor.CommandRequest request = mapper.treeToValue(args, ShellExecutor.CommandRequest.class);
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
                // 不回传 Jackson 原始异常，其中可能包含模型传入的命令与凭据。
                return ToolOutput.failure("Invalid command tool arguments: check JSON fields command, commandType (local/remote) and hostName");
            }
        });
    }

    /**
     * 启动并初始化配置的 stdio MCP 服务，只注册它实际公布的工具。
     * 连接归 Agent 持有，Agent 关闭时一并关闭；安装失败则立即释放连接。
     */
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
