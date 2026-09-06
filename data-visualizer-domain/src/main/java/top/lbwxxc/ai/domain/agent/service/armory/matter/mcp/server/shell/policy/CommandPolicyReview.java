package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy;

import lombok.Value;

import java.util.List;

@Value
public class CommandPolicyReview {
    CommandPolicyDecision decision;
    String reason;
    List<String> commands;

    public boolean isAllowed() {
        return decision == CommandPolicyDecision.ALLOW;
    }
}
