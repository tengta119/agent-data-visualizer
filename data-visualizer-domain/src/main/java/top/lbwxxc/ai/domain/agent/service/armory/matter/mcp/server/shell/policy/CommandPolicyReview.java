package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy;

import lombok.Data;
import lombok.Value;

import java.util.List;

@Value
@Data
public class CommandPolicyReview {
    CommandPolicyDecision decision;
    String reason;
    List<String> commands;

    public boolean isAllowed() {
        return decision == CommandPolicyDecision.ALLOW;
    }

    public boolean isPrompt() {
        return decision == CommandPolicyDecision.PROMPT;
    }

    public boolean isForbidden() {
        return decision == CommandPolicyDecision.FORBIDDEN;
    }
}
