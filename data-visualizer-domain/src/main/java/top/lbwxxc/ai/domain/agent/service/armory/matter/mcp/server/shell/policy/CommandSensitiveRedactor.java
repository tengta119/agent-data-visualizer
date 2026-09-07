package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy;

import java.util.regex.Pattern;

/**
 * 命令脱敏工具。Token、密码、Authorization、私钥和 Secret 不得发送到前端或原样写入日志，
 * 因此本地/远程命令在进入审批事件、审计或前端展示前必须先经过脱敏。
 */
public final class CommandSensitiveRedactor {

    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)(--?(?:password|passwd|token|secret|api[-_]?key)|authorization)\\s*(?:=|\\s)\\s*[^\\s]+"
    );

    private CommandSensitiveRedactor() {
    }

    /**
     * 仅做敏感参数替换脱敏，不做长度截断；截断属于调用方展示策略。
     */
    public static String redact(String command) {
        if (command == null || command.isEmpty()) {
            return command == null ? "" : command;
        }
        return SECRET_PARAMETER.matcher(command).replaceAll("$1=<redacted>");
    }
}
