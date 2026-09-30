package top.lbwxxc.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import top.lbwxxc.ai.domain.agent.service.paicli.IPaiCliWorkflowService;

import jakarta.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Checks startup configuration without invoking an external model. */
@SpringBootTest(properties = "open-ai.key=test-key")
public class AiAgentAutoConfigTest {
    @Resource
    private IPaiCliWorkflowService workflow;

    @Test
    public void startupInstallsThePaiCliSnapshot() {
        assertFalse(workflow.listAgents().isEmpty());
    }
}
