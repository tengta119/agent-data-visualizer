package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;

import java.util.regex.Pattern;

@Slf4j
@Service
public class CommandAuditRecorder {

    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)(--?(?:password|passwd|token|secret|api[-_]?key)|authorization)\\s*(?:=|\\s)\\s*[^\\s]+"
    );

    public void record(ShellExecutor.CommandRequest request, CommandPolicyReview review, String status) {
        String command = sanitize(request == null ? null : request.getCommand());
        String commandType = request == null || request.getCommandType() == null
                ? "unknown" : request.getCommandType().name();
        String host = request == null || request.getHostName() == null ? "" : request.getHostName();
        log.info("command_audit type={} host={} decision={} status={} reason={} command={}",
                commandType, host, review == null ? "unknown" : review.getDecision(), status,
                review == null ? "unknown" : review.getReason(), command);
    }

    private String sanitize(String command) {
        if (command == null) return "";
        String sanitized = SECRET_PARAMETER.matcher(command).replaceAll("$1=<redacted>");
        return sanitized.length() > 300 ? sanitized.substring(0, 300) + "..." : sanitized;
    }
}
