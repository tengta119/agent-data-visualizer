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
}
