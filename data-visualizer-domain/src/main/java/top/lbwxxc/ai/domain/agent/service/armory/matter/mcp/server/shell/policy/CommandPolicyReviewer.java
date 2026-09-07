package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy;

import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CommandPolicyReviewer {

    private static final String[] UNSUPPORTED_OPERATORS = {
            "|", ">", "<", "`", "$(", "${", "&", "(", ")", "{", "}"
    };
    private static final Set<String> FORBIDDEN_COMMANDS = Set.of("rm");
    private static final Set<String> FORBIDDEN_SUBCOMMANDS = Set.of("rm");

    private final CommandExecutionPolicyProperties properties;

    public CommandPolicyReviewer(CommandExecutionPolicyProperties properties) {
        this.properties = properties;
    }

    public CommandPolicyReview review(ShellExecutor.CommandRequest request) {
        if (request == null || request.getCommand() == null || request.getCommand().isBlank()) {
            return forbidden("命令不能为空", Collections.emptyList());
        }
        if (request.getCommand().length() > properties.getMaxCommandLength()) {
            return forbidden("命令长度超过限制", Collections.emptyList());
        }
        if (request.getCommandType() == null) {
            return forbidden("命令类型不能为空", Collections.emptyList());
        }

        String host = request.getHostName() == null ? "" : request.getHostName().trim();
        if (request.getCommandType() == ShellExecutor.CommandTypeEnum.local && !host.isEmpty()) {
            return forbidden("local 命令不允许指定远程 host", Collections.emptyList());
        }
        if (request.getCommandType() == ShellExecutor.CommandTypeEnum.remote
                && !properties.getRemoteAllowedHosts().contains(host)) {
            return forbidden("远程 host 不在允许列表中", Collections.emptyList());
        }

        List<String> commands = splitCommands(request.getCommand().trim());
        if (commands == null || commands.isEmpty()) {
            return forbidden("命令包含不支持或无法解析的 shell 结构", Collections.emptyList());
        }

        boolean localCommand = request.getCommandType() == ShellExecutor.CommandTypeEnum.local;
        List<String> allowRules = localCommand ? properties.getLocalAllow() : properties.getRemoteAllow();
        List<String> promptRules = localCommand ? properties.getLocalPrompt() : properties.getRemotePrompt();
        boolean promptMatched = false;
        for (String subCommand : commands) {
            List<String> tokens = tokenize(subCommand);
            if (tokens == null || tokens.isEmpty()) {
                return forbidden("命令包含无法解析的参数", commands);
            }
            String executable = executableName(tokens.get(0));
            if (FORBIDDEN_COMMANDS.contains(executable.toLowerCase(Locale.ROOT))) {
                return forbidden("命令包含禁止的执行器: " + executable, commands);
            }
            for (String token : tokens.subList(1, tokens.size())) {
                if (FORBIDDEN_SUBCOMMANDS.contains(token.toLowerCase(Locale.ROOT))) {
                    return forbidden("命令包含禁止的参数: " + token, commands);
                }
            }
            if (matchesAnyPrefix(tokens, allowRules)) {
                continue;
            }
            if (matchesAnyPrefix(tokens, promptRules)) {
                promptMatched = true;
                continue;
            }
            return forbidden("命令未命中允许或审批规则: " + subCommand.trim(), commands);
        }
        promptMatched = true;
        if (promptMatched) {
            return new CommandPolicyReview(CommandPolicyDecision.PROMPT, "命令需要用户审批后执行", commands);
        }
        return new CommandPolicyReview(CommandPolicyDecision.ALLOW, "命令命中允许规则", commands);
    }

    private List<String> splitCommands(String command) {
        List<String> commands = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\0' || c == '\r' || c == '\n') {
                return null;
            }
            if (c == '\'' || c == '"') {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
                current.append(c);
                continue;
            }
            if (quote == 0) {
                if (containsAt(command, i, "&&") || containsAt(command, i, "||")) {
                    if (current.toString().trim().isEmpty()) return null;
                    commands.add(current.toString().trim());
                    current.setLength(0);
                    i++;
                    continue;
                }
                if (c == ';') {
                    if (current.toString().trim().isEmpty()) return null;
                    commands.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
                for (String operator : UNSUPPORTED_OPERATORS) {
                    if ((operator.length() == 1 && c == operator.charAt(0))
                            || (operator.length() > 1 && containsAt(command, i, operator))) {
                        return null;
                    }
                }
            }
            current.append(c);
        }
        if (quote != 0 || current.toString().trim().isEmpty()) return null;
        commands.add(current.toString().trim());
        return commands;
    }

    private List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && quote != '\'') {
                escaped = true;
                continue;
            }
            if (c == '\'' || c == '"') {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
                else current.append(c);
                continue;
            }
            if (quote == 0 && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (escaped || quote != 0) return null;
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    private boolean matchesAnyPrefix(List<String> tokens, List<String> rules) {
        if (rules == null) return false;
        for (String rule : rules) {
            List<String> ruleTokens = tokenize(rule == null ? "" : rule.trim());
            if (ruleTokens == null || ruleTokens.isEmpty() || ruleTokens.size() > tokens.size()) continue;
            boolean matches = true;
            for (int i = 0; i < ruleTokens.size(); i++) {
                if (!ruleTokens.get(i).equalsIgnoreCase(tokens.get(i))) {
                    matches = false;
                    break;
                }
            }
            if (matches) return true;
        }
        return false;
    }

    private String executableName(String token) {
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        return slash >= 0 ? token.substring(slash + 1) : token;
    }

    private boolean containsAt(String value, int index, String part) {
        return index + part.length() <= value.length() && value.startsWith(part, index);
    }

    private CommandPolicyReview forbidden(String reason, List<String> commands) {
        return new CommandPolicyReview(CommandPolicyDecision.FORBIDDEN, reason, commands);
    }
}
