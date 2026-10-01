package top.lbwxxc.ai.domain.agent.service.paicli;

import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates and freezes the YAML configuration before a runtime snapshot is published. */
final class PaiCliConfigCompiler {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");
    private static final List<String> DRAWING_SKILLS = List.of(
            "drawio-uml", "drawio-sequence", "drawio-flowchart", "drawio-architecture");

    /**
     * 将整份配置校验并编译为不可变的 Agent 定义集合。
     * 任一表无效就拒绝整次更新，避免运行时发布只有部分 Agent 可用的快照。
     */
    Map<String, AgentDefinition> compile(AiAgentAutoConfigProperties config) {
        if (config == null || config.getTables() == null || config.getTables().isEmpty()) {
            throw invalid("Agent tables must not be empty");
        }
        Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
        Set<String> appNames = new HashSet<>();
        Set<String> displayNames = new HashSet<>();
        for (Map.Entry<String, AiAgentConfigTableVO> entry : config.getTables().entrySet()) {
            AgentDefinition definition = compileTable(entry.getKey(), entry.getValue());
            if (!appNames.add(entry.getValue().getAppName().trim())) {
                throw invalid("Duplicate app name");
            }
            if (!displayNames.add(entry.getValue().getAgent().getAgentName().trim())) {
                throw invalid("Duplicate agent name");
            }
            if (definitions.putIfAbsent(definition.agentId(), definition) != null) {
                throw invalid("Duplicate agent ID: " + definition.agentId());
            }
        }
        return Map.copyOf(definitions);
    }

    /** 校验单个 Agent 的模型、阶段、工作流与工具引用，并确定 Runner 入口。 */
    private AgentDefinition compileTable(String tableName, AiAgentConfigTableVO table) {
        if (table == null || table.getAgent() == null || table.getModule() == null) {
            throw invalid("Missing agent or module in table: " + tableName);
        }
        required(tableName, "table name");
        required(table.getAppName(), "app-name");
        required(table.getAgent().getAgentName(), "agent-name");
        String agentId = required(table.getAgent().getAgentId(), "agent-id");
        AiAgentConfigTableVO.Module module = table.getModule();
        if (module.getAiApi() == null || module.getChatModel() == null) {
            throw invalid("Missing ai-api or chat-model for agent: " + agentId);
        }
        AiAgentConfigTableVO.Module.AiApi api = module.getAiApi();
        String apiKey = required(api.getApiKey(), "api-key");
        if (apiKey.contains("${")) {
            throw invalid("api-key has an unresolved placeholder");
        }
        PaiCliModelFactory.ModelSettings model = new PaiCliModelFactory.ModelSettings(
                endpoint(api.getBaseUrl(), api.getCompletionsPath()), apiKey,
                required(module.getChatModel().getModel(), "model"));
        validateTools(module.getChatModel());
        validatePlugins(module.getRunner());
        String skillText = loadSkills(module.getChatModel().getToolSkillsList());
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> tools = module.getChatModel().getToolMcpList() == null
                ? List.of() : List.copyOf(module.getChatModel().getToolMcpList());

        Map<String, StageSpec> stages = new LinkedHashMap<>();
        if (module.getAgents() == null || module.getAgents().isEmpty()) {
            throw invalid("No stages for agent: " + agentId);
        }
        Set<String> outputKeys = new HashSet<>();
        for (AiAgentConfigTableVO.Module.Agent stage : module.getAgents()) {
            if (stage == null) {
                throw invalid("Null stage in agent: " + agentId);
            }
            String name = required(stage.getName(), "stage name");
            String outputKey = required(stage.getOutputKey(), "output-key of " + name);
            String instruction = required(stage.getInstruction(), "instruction of " + name);
            if (stages.putIfAbsent(name, new StageSpec(name, instruction, outputKey, skillText, tools)) != null) {
                throw invalid("Duplicate stage name: " + name);
            }
            outputKeys.add(outputKey);
        }
        for (StageSpec stage : stages.values()) {
            Matcher matcher = PLACEHOLDER.matcher(stage.instruction());
            while (matcher.find()) {
                if (!outputKeys.contains(matcher.group(1))) {
                    throw invalid("Unknown output placeholder in stage " + stage.name() + ": " + matcher.group(1));
                }
            }
        }

        Map<String, WorkflowSpec> workflows = new LinkedHashMap<>();
        if (module.getAgentWorkflows() != null) {
            for (AiAgentConfigTableVO.Module.AgentWorkflow workflow : module.getAgentWorkflows()) {
                if (workflow == null) {
                    throw invalid("Null workflow in agent: " + agentId);
                }
                String name = required(workflow.getName(), "workflow name");
                if (stages.containsKey(name) || workflows.containsKey(name)) {
                    throw invalid("Duplicate stage or workflow name: " + name);
                }
                WorkflowType type;
                try {
                    type = WorkflowType.valueOf(required(workflow.getType(), "workflow type")
                            .toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException error) {
                    throw invalid("Unsupported workflow type: " + workflow.getType());
                }
                List<String> children = workflow.getSubAgents();
                if (children == null || children.isEmpty()) {
                    throw invalid("Workflow has no sub-agents: " + name);
                }
                if (children.stream().anyMatch(child -> child == null || child.isBlank())) {
                    throw invalid("Workflow has a blank sub-agent: " + name);
                }
                int iterations = workflow.getMaxIterations() == null ? 3 : workflow.getMaxIterations();
                if (type == WorkflowType.LOOP && (iterations < 1 || iterations > 100)) {
                    throw invalid("Invalid max-iterations in workflow: " + name);
                }
                workflows.put(name, new WorkflowSpec(name, type, List.copyOf(children), iterations));
            }
        }
        for (WorkflowSpec workflow : workflows.values()) {
            for (String child : workflow.children()) {
                if (!stages.containsKey(child) && !workflows.containsKey(child)) {
                    throw invalid("Unknown sub-agent in workflow " + workflow.name() + ": " + child);
                }
            }
        }
        detectCycles(workflows);
        if (module.getRunner() == null) {
            throw invalid("Missing runner for agent: " + agentId);
        }
        String entry = required(module.getRunner().getAgentName(), "runner.agent-name");
        if (!stages.containsKey(entry) && !workflows.containsKey(entry)) {
            throw invalid("Unknown runner entry: " + entry);
        }
        return new AgentDefinition(agentId, model, Map.copyOf(stages), Map.copyOf(workflows), entry);
    }

    /** 工作流之间允许嵌套引用，但递归环会让执行无法终止，必须在发布前拒绝。 */
    private void detectCycles(Map<String, WorkflowSpec> workflows) {
        Map<String, Integer> state = new HashMap<>();
        for (String name : workflows.keySet()) {
            visit(name, workflows, state);
        }
    }

    private void visit(String name, Map<String, WorkflowSpec> workflows, Map<String, Integer> state) {
        if (state.getOrDefault(name, 0) == 1) {
            throw invalid("Workflow reference cycle at: " + name);
        }
        if (state.getOrDefault(name, 0) == 2) {
            return;
        }
        state.put(name, 1);
        for (String child : workflows.get(name).children()) {
            if (workflows.containsKey(child)) {
                visit(child, workflows, state);
            }
        }
        state.put(name, 2);
    }

    private void validateTools(AiAgentConfigTableVO.Module.ChatModel model) {
        if (model.getToolMcpList() == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp tool : model.getToolMcpList()) {
            if (tool == null) {
                throw invalid("Null MCP tool configuration");
            }
            int choices = (tool.getLocal() == null ? 0 : 1) + (tool.getStdio() == null ? 0 : 1)
                    + (tool.getSse() == null ? 0 : 1);
            if (choices != 1) {
                throw invalid("MCP tool must declare exactly one transport");
            }
            if (tool.getLocal() != null
                    && !"ShellExecutor".equals(tool.getLocal().getName())
                    && !"ShellExecutorToolCallbackProvider".equals(tool.getLocal().getName())) {
                throw invalid("Unsupported local tool: " + tool.getLocal().getName());
            }
            String name = tool.getLocal() != null ? "ShellExecutor" :
                    tool.getStdio() != null ? tool.getStdio().getName() : tool.getSse().getName();
            if (name == null || name.isBlank() || !names.add(name)) {
                throw invalid("Missing or duplicate MCP server name");
            }
            if (tool.getStdio() != null) {
                var stdio = tool.getStdio();
                if (stdio.getName() == null || stdio.getName().isBlank()
                        || stdio.getServerParameters() == null
                        || stdio.getServerParameters().getCommand() == null
                        || stdio.getServerParameters().getCommand().isBlank()) {
                    throw invalid("Invalid stdio MCP configuration");
                }
            }
            if (tool.getSse() != null) {
                throw invalid("Legacy SSE MCP transport is not supported by the embedded PaiCLI runtime");
            }
        }
    }

    private void validatePlugins(AiAgentConfigTableVO.Module.Runner runner) {
        if (runner == null || runner.getPluginNameList() == null) {
            return;
        }
        if (!runner.getPluginNameList().isEmpty()) {
            throw invalid("ADK runner plugins are no longer supported; remove plugin-name-list");
        }
    }

    private String loadSkills(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills skill : skills) {
            if (skill == null || skill.getPath() == null || skill.getPath().isBlank()) {
                throw invalid("Unsupported skill configuration");
            }
            if ("directory".equals(skill.getType())) {
                Path directory = Path.of(skill.getPath()).toAbsolutePath().normalize();
                if (!Files.isDirectory(directory)) {
                    throw invalid("Skill directory does not exist: " + directory);
                }
                try (var files = Files.walk(directory)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                            .sorted().toList()) {
                        content.append("\n\n## Skill: ").append(file.getParent().getFileName()).append("\n")
                                .append(Files.readString(file, StandardCharsets.UTF_8));
                    }
                } catch (IOException error) {
                    throw invalid("Cannot read skill directory: " + directory);
                }
                continue;
            }
            if (!"resource".equals(skill.getType()) || !"agent/skills".equals(skill.getPath())) {
                throw invalid("Unsupported skill configuration");
            }
            for (String name : DRAWING_SKILLS) {
                String path = "agent/skills/" + name + "/SKILL.md";
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
                    if (stream == null) {
                        throw invalid("Missing drawing skill resource: " + path);
                    }
                    content.append("\n\n## Drawing skill: ").append(name).append("\n")
                            .append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException error) {
                    throw invalid("Cannot read drawing skill resource: " + path);
                }
            }
        }
        return content.toString();
    }

    private String endpoint(String baseUrl, String completionsPath) {
        String base = required(baseUrl, "base-url");
        String path = required(completionsPath, "completions-path").replaceFirst("^/+", "");
        try {
            URI uri = URI.create(base);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || path.contains("..") || path.contains("?")
                    || path.contains("#") || path.contains(":") || path.isBlank()) {
                throw invalid("Invalid model endpoint configuration");
            }
            String normalizedBase = base.replaceFirst("/+$", "");
            if (normalizedBase.endsWith("/v1") && path.startsWith("v1/")) {
                path = path.substring(3);
            }
            String endpoint = normalizedBase + "/" + path;
            URI resolved = URI.create(endpoint);
            if (resolved.getHost() == null) {
                throw invalid("Invalid model endpoint configuration");
            }
            return endpoint;
        } catch (IllegalArgumentException error) {
            throw invalid("Invalid model endpoint configuration");
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("Missing " + field);
        }
        return value.trim();
    }

    private PaiCliWorkflowException invalid(String message) {
        return new PaiCliWorkflowException(PaiCliWorkflowException.Reason.CONFIG_INVALID, message);
    }

    record AgentDefinition(String agentId, PaiCliModelFactory.ModelSettings model,
                           Map<String, StageSpec> stages, Map<String, WorkflowSpec> workflows, String entry) {
    }

    record StageSpec(String name, String instruction, String outputKey, String skills,
                     List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> tools) {
    }

    record WorkflowSpec(String name, WorkflowType type, List<String> children, int iterations) {
    }

    enum WorkflowType {
        SEQUENTIAL, PARALLEL, LOOP
    }
}
