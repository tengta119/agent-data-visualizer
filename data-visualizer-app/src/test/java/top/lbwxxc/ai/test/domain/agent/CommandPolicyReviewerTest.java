package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandExecutionPolicyProperties;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyDecision;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReviewer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandPolicyReviewerTest {

    private final CommandExecutionPolicyProperties properties = new CommandExecutionPolicyProperties();
    private final CommandPolicyReviewer reviewer = new CommandPolicyReviewer(properties);

    @Test
    void allowsOnlyConfiguredLocalCommand() {
        ShellExecutor.CommandRequest request = request("pwd", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.ALLOW, reviewer.review(request).getDecision());
    }

    @Test
    void rejectsUnlistedCommandAndDoesNotTreatPrefixAsExactExecutable() {
        ShellExecutor.CommandRequest request = request("pwd-secret", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    @Test
    void rejectsShellOperatorsAsAWholeRequest() {
        ShellExecutor.CommandRequest request = request("pwd && rm -rf /", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    @Test
    void rejectsRemoteHostUnlessItIsExplicitlyConfigured() {
        properties.setRemoteAllowedHosts(List.of("client-a"));
        ShellExecutor.CommandRequest request = request("pwd", ShellExecutor.CommandTypeEnum.remote, "client-b");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    @Test
    void validatesRemoteHostAndCommandSeparately() {
        properties.setRemoteAllowedHosts(List.of("client-a"));
        ShellExecutor.CommandRequest request = request("rm -rf /", ShellExecutor.CommandTypeEnum.remote, "client-a");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    @Test
    void promptsWhenCommandHitsConfiguredPromptRule() {
        properties.setLocalPrompt(List.of("git push", "git add"));
        ShellExecutor.CommandRequest request = request("git push origin main", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.PROMPT, reviewer.review(request).getDecision());
    }

    @Test
    void defaultsToForbiddenWhenNoPromptRuleConfigured() {
        ShellExecutor.CommandRequest request = request("git push origin main", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    @Test
    void allowRuleBeatsPromptRuleForSameExecutable() {
        properties.setLocalPrompt(List.of("pwd"));
        ShellExecutor.CommandRequest request = request("pwd", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.ALLOW, reviewer.review(request).getDecision());
    }

    @Test
    void allowPlusPromptMergesToPrompt() {
        properties.setLocalPrompt(List.of("git push"));
        ShellExecutor.CommandRequest request = request("pwd && git push origin main", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.PROMPT, reviewer.review(request).getDecision());
    }

    @Test
    void promptPlusForbiddenSubCommandStaysForbidden() {
        properties.setLocalPrompt(List.of("git push"));
        ShellExecutor.CommandRequest request = request("git push origin main && rm -rf /", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    @Test
    void forbiddenOperatorCannotBecomePrompt() {
        properties.setLocalPrompt(List.of("git push"));
        ShellExecutor.CommandRequest request = request("git push origin main | grep error", ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    @Test
    void promptRulesAreIsolatedByCommandType() {
        properties.setRemoteAllowedHosts(List.of("client-a"));
        properties.setLocalPrompt(List.of("git push"));
        ShellExecutor.CommandRequest request = request("git push origin main", ShellExecutor.CommandTypeEnum.remote, "client-a");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    @Test
    void remotePromptOnlyAppliesToAllowedHost() {
        properties.setRemoteAllowedHosts(List.of("client-a"));
        properties.setRemotePrompt(List.of("git push"));
        ShellExecutor.CommandRequest request = request("git push origin main", ShellExecutor.CommandTypeEnum.remote, "client-a");

        assertEquals(CommandPolicyDecision.PROMPT, reviewer.review(request).getDecision());
    }

    @Test
    void overLengthCommandCannotBecomePrompt() {
        properties.setLocalPrompt(List.of("git push"));
        String longCommand = "git push origin main " + "x".repeat(properties.getMaxCommandLength() + 10);
        ShellExecutor.CommandRequest request = request(longCommand, ShellExecutor.CommandTypeEnum.local, "");

        assertEquals(CommandPolicyDecision.FORBIDDEN, reviewer.review(request).getDecision());
    }

    private ShellExecutor.CommandRequest request(String command, ShellExecutor.CommandTypeEnum type, String host) {
        return new ShellExecutor.CommandRequest(command, type, host);
    }
}
