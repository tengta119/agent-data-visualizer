package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "command.execution.policy")
public class CommandExecutionPolicyProperties {

    private int maxCommandLength = 1000;
    private int maxOutputChars = 1024 * 1024;
    private List<String> localAllow = new ArrayList<>(List.of(
            "clients",
            "pwd",
            "ls",
            "dir",
            "whoami",
            "uname",
            "git status",
            "git diff"
    ));
    private List<String> remoteAllow = new ArrayList<>(List.of(
            "pwd",
            "ls",
            "dir",
            "whoami",
            "uname",
            "git status",
            "git diff"
    ));
    private List<String> remoteAllowedHosts = new ArrayList<>();

    /**
     * 需要用户交互审批的本地命令前缀规则；默认空列表表示不产生审批。
     * 命中 allow 规则的命令优先直接执行，不会进入审批。
     */
    private List<String> localPrompt = new ArrayList<>();

    /**
     * 需要用户交互审批的远程命令前缀规则；默认空列表表示不产生审批。
     */
    private List<String> remotePrompt = new ArrayList<>();

    /**
     * 单次审批等待用户决定的超时时间（毫秒），默认 60 秒。
     */
    private long approvalTimeoutMillis = 60_000L;

    /**
     * 审批等待超时允许配置的最大值（毫秒），默认 300 秒；防止无限延长等待消耗执行线程。
     */
    private long approvalTimeoutMillisMax = 300_000L;
}
