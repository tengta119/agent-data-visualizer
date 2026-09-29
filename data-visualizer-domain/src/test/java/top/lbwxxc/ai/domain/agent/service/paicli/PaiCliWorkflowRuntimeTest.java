package top.lbwxxc.ai.domain.agent.service.paicli;

import com.paicli.llm.LlmClient;
import com.paicli.embed.EmbeddedTurnException;
import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaiCliWorkflowRuntimeTest {

    @Test
    void defaultThreeStageWorkflowUsesTrustedOutputInterpolation() {
        FakeModels models = new FakeModels((prompt, turn) -> {
            if (prompt.startsWith("ANALYST")) return "analysis: flowchart";
            if (prompt.startsWith("DRAWER")) return "draft: nodes";
            return "final: drawio_graph";
        });
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", defaultStages(),
                List.of(workflow("sequential", "sequential_draw_process", 1,
                        "agent_analyst", "agent_drawer", "agent_reviewer")),
                "sequential_draw_process")));
        String session = runtime.createSession("100003", "user-a");
        List<PaiCliWorkflowEvent> events = new ArrayList<>();

        PaiCliWorkflowResult result = runtime.run("100003", "user-a", session, "画流程图", events::add);

        assertEquals("final: drawio_graph", result.content());
        assertEquals(Map.of("analysis_result", "analysis: flowchart", "draft_diagram", "draft: nodes",
                "final_result", "final: drawio_graph"), result.outputs());
        assertEquals(List.of("agent_analyst", "agent_drawer", "agent_reviewer"),
                events.stream().filter(e -> e.kind() == PaiCliWorkflowEvent.Kind.STAGE_STARTED)
                        .map(PaiCliWorkflowEvent::stage).toList());
        assertTrue(models.calls.get(1).prompt().contains("analysis: flowchart"));
        assertTrue(models.calls.get(2).prompt().contains("draft: nodes"));
        assertFalse(models.calls.get(1).prompt().contains("{analysis_result}"));
        assertTrue(events.stream().anyMatch(e -> e.kind() == PaiCliWorkflowEvent.Kind.CONTENT_DELTA));
    }

    @Test
    void supplementationRequestRemainsTheFinalUserResult() {
        String question = "{\"type\":\"user\",\"content\":\"请补充节点信息\"}";
        FakeModels models = new FakeModels((prompt, turn) -> question);
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", defaultStages(),
                List.of(workflow("sequential", "sequential_draw_process", 1,
                        "agent_analyst", "agent_drawer", "agent_reviewer")),
                "sequential_draw_process")));

        PaiCliWorkflowResult result = runtime.run("100003", "u", runtime.createSession("100003", "u"), "画图");

        assertEquals(question, result.content());
        assertEquals(question, result.outputs().get("final_result"));
        assertEquals(3, models.calls.size());
    }

    @Test
    void parallelBranchesSeeOnlyTheirInputSnapshotAndMergeInConfiguredOrder() {
        FakeModels models = new FakeModels((prompt, turn) -> {
            if (prompt.startsWith("SEED")) return "seed-value";
            if (prompt.startsWith("A")) return "branch-a";
            return "branch-b";
        });
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", List.of(
                stage("seed", "SEED", "seed_value"),
                stage("left", "A sees {seed_value}", "left_value"),
                stage("right", "B sees {seed_value}", "right_value")),
                List.of(workflow("parallel", "branches", 1, "left", "right"),
                        workflow("sequential", "entry", 1, "seed", "branches")), "entry")));

        PaiCliWorkflowResult result = runtime.run("100003", "u", runtime.createSession("100003", "u"), "request");

        assertEquals("branch-b", result.content());
        assertEquals("branch-a", result.outputs().get("left_value"));
        assertEquals("branch-b", result.outputs().get("right_value"));
        assertTrue(models.calls.stream().filter(c -> c.prompt().startsWith("B"))
                .allMatch(c -> c.prompt().contains("seed-value") && !c.prompt().contains("branch-a")));
    }

    @Test
    void failedParallelTurnWaitsForOtherBranchBeforeReleasingSession() throws Exception {
        CountDownLatch rightEntered = new CountDownLatch(1);
        CountDownLatch releaseRight = new CountDownLatch(1);
        FakeModels models = new FakeModels((prompt, turn) -> {
            try {
                if (prompt.startsWith("LEFT")) {
                    assertTrue(rightEntered.await(2, TimeUnit.SECONDS));
                    throw new IllegalStateException("left failed");
                }
                rightEntered.countDown();
                assertTrue(releaseRight.await(2, TimeUnit.SECONDS));
                return "right-result";
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        });
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", List.of(
                stage("left", "LEFT", "left_value"), stage("right", "RIGHT", "right_value")),
                List.of(workflow("parallel", "entry", 1, "left", "right")), "entry")));
        String session = runtime.createSession("100003", "alice");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> runInto(runtime, session, "request", failure));
        worker.start();
        assertTrue(rightEntered.await(2, TimeUnit.SECONDS));
        try {
            worker.join(100);
            assertTrue(worker.isAlive());
        } finally {
            releaseRight.countDown();
            worker.join(2_000);
        }
        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof EmbeddedTurnException);
    }

    @Test
    void loopStopsAtConfiguredMaximumAndReusesStageHistory() {
        FakeModels models = new FakeModels((prompt, turn) -> "iteration-" + turn);
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", List.of(stage("refine", "REFINE", "draft")),
                List.of(workflow("loop", "repeat", 3, "refine")), "repeat")));

        PaiCliWorkflowResult result = runtime.run("100003", "u", runtime.createSession("100003", "u"), "request");

        assertEquals("iteration-3", result.content());
        assertEquals(3, models.calls.size());
        assertEquals(List.of(1, 2, 3), models.calls.stream().map(Call::userMessages).toList());
    }

    @Test
    void sessionHistoryIsPrivateAndWrongOwnershipIsRejected() {
        FakeModels models = new FakeModels((prompt, turn) -> "turn-" + turn);
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", List.of(stage("only", "ONE", "answer")), List.of(), "only")));
        String first = runtime.createSession("100003", "alice");
        String second = runtime.createSession("100003", "alice");

        assertEquals("turn-1", runtime.run("100003", "alice", first, "one").content());
        assertEquals("turn-2", runtime.run("100003", "alice", first, "two").content());
        assertEquals("turn-1", runtime.run("100003", "alice", second, "one").content());
        assertEquals(PaiCliWorkflowException.Reason.SESSION_MISMATCH,
                assertThrows(PaiCliWorkflowException.class,
                        () -> runtime.run("100003", "bob", first, "request")).reason());
        assertEquals(PaiCliWorkflowException.Reason.SESSION_NOT_FOUND,
                assertThrows(PaiCliWorkflowException.class,
                        () -> runtime.run("100003", "alice", "unknown", "request")).reason());
    }

    @Test
    void failedUpdateKeepsSnapshotAndSuccessfulUpdateExpiresOldSessions() {
        FakeModels models = new FakeModels((prompt, turn) -> "ok");
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        AiAgentConfigTableVO first = table("100003", List.of(stage("only", "ONE", "answer")), List.of(), "only");
        AiAgentConfigTableVO removed = table("100004", List.of(stage("only", "TWO", "answer")), List.of(), "only");
        runtime.install(config(first, removed));
        String oldSession = runtime.createSession("100003", "alice");
        long originalVersion = runtime.version();
        AiAgentConfigTableVO invalid = table("100003", List.of(stage("only", "ONE", "answer")),
                List.of(workflow("loop", "bad", 0, "only")), "bad");

        assertEquals(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                assertThrows(PaiCliWorkflowException.class, () -> runtime.install(config(invalid))).reason());
        assertEquals(originalVersion, runtime.version());
        assertEquals(2, runtime.listAgents().size());
        runtime.currentConfiguration().getTables().clear();
        assertEquals(2, runtime.listAgents().size());
        assertEquals("ok", runtime.run("100003", "alice", oldSession, "request").content());

        runtime.install(config(first));
        assertEquals(1, runtime.listAgents().size());
        assertEquals(PaiCliWorkflowException.Reason.AGENT_NOT_FOUND,
                assertThrows(PaiCliWorkflowException.class,
                        () -> runtime.createSession("100004", "alice")).reason());
        assertEquals(PaiCliWorkflowException.Reason.SESSION_EXPIRED,
                assertThrows(PaiCliWorkflowException.class,
                        () -> runtime.run("100003", "alice", oldSession, "request")).reason());
        assertEquals("ok", runtime.run("100003", "alice",
                runtime.createSession("100003", "alice"), "request").content());
    }

    @Test
    void sameSessionTurnsDoNotRunConcurrently() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        FakeModels models = new FakeModels((prompt, turn) -> {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            try {
                if (turn == 1) {
                    entered.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                }
                return "turn-" + turn;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            } finally {
                active.decrementAndGet();
            }
        });
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", List.of(stage("only", "ONE", "answer")), List.of(), "only")));
        String session = runtime.createSession("100003", "alice");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> runInto(runtime, session, "one", failure));
        Thread second = new Thread(() -> runInto(runtime, session, "two", failure));
        first.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        second.start();
        release.countDown();
        first.join(2_000);
        second.join(2_000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(null, failure.get());
        assertEquals(1, maximum.get());
        assertEquals(List.of(1, 2), models.calls.stream().map(Call::userMessages).toList());
    }

    @Test
    void eventCallbackCannotReenterTheSameSession() {
        FakeModels models = new FakeModels((prompt, turn) -> "done");
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", List.of(stage("only", "ONE", "answer")), List.of(), "only")));
        String session = runtime.createSession("100003", "alice");

        PaiCliWorkflowResult result = runtime.run("100003", "alice", session, "outer", event -> {
            if (event.kind() == PaiCliWorkflowEvent.Kind.STAGE_STARTED) {
                assertEquals(PaiCliWorkflowException.Reason.WORKFLOW_STATE,
                        assertThrows(PaiCliWorkflowException.class,
                                () -> runtime.run("100003", "alice", session, "inner")).reason());
            }
        });

        assertEquals("done", result.content());
        assertEquals(1, models.calls.size());
    }

    @Test
    void inFlightTurnCompletesOnItsOriginalConfigurationSnapshot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        FakeModels models = new FakeModels((prompt, turn) -> {
            if (firstCall.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return prompt.startsWith("OLD") ? "old-result" : "new-result";
        });
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        runtime.install(config(table("100003", List.of(stage("only", "OLD", "answer")), List.of(), "only")));
        String session = runtime.createSession("100003", "alice");
        AtomicReference<PaiCliWorkflowResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(runtime.run("100003", "alice", session, "request"));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        runtime.install(config(table("100003", List.of(stage("only", "NEW", "answer")), List.of(), "only")));
        release.countDown();
        worker.join(2_000);

        assertFalse(worker.isAlive());
        assertEquals(null, failure.get());
        assertEquals("old-result", result.get().content());
        assertEquals(PaiCliWorkflowException.Reason.SESSION_EXPIRED,
                assertThrows(PaiCliWorkflowException.class,
                        () -> runtime.run("100003", "alice", session, "request")).reason());
        assertEquals("new-result", runtime.run("100003", "alice",
                runtime.createSession("100003", "alice"), "request").content());
    }

    @Test
    void cancellationChecksOwnershipAndAllowsAFollowingTurn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean canceled = new AtomicBoolean();
        LlmClient client = new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                    throws IOException {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", error);
                }
                if (canceled.get()) {
                    throw new IOException("cancelled");
                }
                return new ChatResponse("assistant", "done", List.of(), 1, 1);
            }

            @Override
            public void cancelInFlightCalls() {
                canceled.set(true);
                release.countDown();
            }

            @Override
            public void prepareForRun() {
                canceled.set(false);
            }

            @Override
            public String getModelName() {
                return "fake";
            }

            @Override
            public String getProviderName() {
                return "fake";
            }
        };
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(settings -> client);
        runtime.install(config(table("100003", List.of(stage("only", "ONE", "answer")), List.of(), "only")));
        String session = runtime.createSession("100003", "alice");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> runInto(runtime, session, "first", failure));
        worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        assertEquals(PaiCliWorkflowException.Reason.SESSION_MISMATCH,
                assertThrows(PaiCliWorkflowException.class,
                        () -> runtime.cancel("100003", "bob", session)).reason());
        runtime.cancel("100003", "alice", session);
        worker.join(2_000);

        assertFalse(worker.isAlive());
        assertEquals(EmbeddedTurnException.Kind.CANCELLED,
                ((EmbeddedTurnException) failure.get()).kind());
        assertEquals("done", runtime.run("100003", "alice", session, "second").content());
    }

    @Test
    void rejectsUnknownReferencesUnsupportedToolsAndMissingModelWithoutLeakingKey() {
        FakeModels models = new FakeModels((prompt, turn) -> "unused");
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(models);
        AiAgentConfigTableVO unknown = table("100003", List.of(stage("only", "ONE", "answer")),
                List.of(workflow("sequential", "entry", 1, "missing")), "entry");
        assertEquals(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                assertThrows(PaiCliWorkflowException.class, () -> runtime.install(config(unknown))).reason());

        AiAgentConfigTableVO cycle = table("100003", List.of(stage("only", "ONE", "answer")),
                List.of(workflow("sequential", "first", 1, "second"),
                        workflow("sequential", "second", 1, "first")), "first");
        assertEquals(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                assertThrows(PaiCliWorkflowException.class, () -> runtime.install(config(cycle))).reason());

        AiAgentConfigTableVO duplicateName = table("100004", List.of(stage("only", "TWO", "answer")),
                List.of(), "only");
        duplicateName.getAgent().setAgentName("agent-100003");
        assertEquals(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                assertThrows(PaiCliWorkflowException.class,
                        () -> runtime.install(config(table("100003", List.of(stage("only", "ONE", "answer")),
                                List.of(), "only"), duplicateName))).reason());

        AiAgentConfigTableVO unsupported = table("100003", List.of(stage("only", "ONE", "answer")),
                List.of(), "only");
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp tool = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp();
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local =
                new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters();
        local.setName("unknown-tool");
        tool.setLocal(local);
        unsupported.getModule().getChatModel().setToolMcpList(List.of(tool));
        assertEquals(PaiCliWorkflowException.Reason.CONFIG_INVALID,
                assertThrows(PaiCliWorkflowException.class, () -> runtime.install(config(unsupported))).reason());

        AiAgentConfigTableVO missingModel = table("100003", List.of(stage("only", "ONE", "answer")),
                List.of(), "only");
        missingModel.getModule().getChatModel().setModel(null);
        missingModel.getModule().getAiApi().setApiKey("super-secret-key");
        PaiCliWorkflowException failure = assertThrows(PaiCliWorkflowException.class,
                () -> runtime.install(config(missingModel)));
        assertFalse(failure.getMessage().contains("super-secret-key"));
    }

    private static List<AiAgentConfigTableVO.Module.Agent> defaultStages() {
        return List.of(stage("agent_analyst", "ANALYST", "analysis_result"),
                stage("agent_drawer", "DRAWER {analysis_result}", "draft_diagram"),
                stage("agent_reviewer", "REVIEWER {draft_diagram}", "final_result"));
    }

    private static void runInto(PaiCliWorkflowRuntime runtime, String session, String input,
                                AtomicReference<Throwable> failure) {
        try {
            runtime.run("100003", "alice", session, input);
        } catch (Throwable error) {
            failure.set(error);
        }
    }

    private static AiAgentAutoConfigProperties config(AiAgentConfigTableVO... tables) {
        AiAgentAutoConfigProperties config = new AiAgentAutoConfigProperties();
        Map<String, AiAgentConfigTableVO> byName = new LinkedHashMap<>();
        for (AiAgentConfigTableVO table : tables) {
            byName.put(table.getAppName(), table);
        }
        config.setTables(byName);
        return config;
    }

    private static AiAgentConfigTableVO table(String id, List<AiAgentConfigTableVO.Module.Agent> stages,
                                               List<AiAgentConfigTableVO.Module.AgentWorkflow> workflows,
                                               String entry) {
        AiAgentConfigTableVO table = new AiAgentConfigTableVO();
        table.setAppName("table-" + id);
        AiAgentConfigTableVO.Agent identity = new AiAgentConfigTableVO.Agent();
        identity.setAgentId(id);
        identity.setAgentName("agent-" + id);
        table.setAgent(identity);
        AiAgentConfigTableVO.Module module = new AiAgentConfigTableVO.Module();
        AiAgentConfigTableVO.Module.AiApi api = new AiAgentConfigTableVO.Module.AiApi();
        api.setBaseUrl("https://api.example.test");
        api.setApiKey("test-key");
        api.setCompletionsPath("v1/chat/completions");
        module.setAiApi(api);
        AiAgentConfigTableVO.Module.ChatModel model = new AiAgentConfigTableVO.Module.ChatModel();
        model.setModel("fake-model");
        module.setChatModel(model);
        module.setAgents(stages);
        module.setAgentWorkflows(workflows);
        AiAgentConfigTableVO.Module.Runner runner = new AiAgentConfigTableVO.Module.Runner();
        runner.setAgentName(entry);
        module.setRunner(runner);
        table.setModule(module);
        return table;
    }

    private static AiAgentConfigTableVO.Module.Agent stage(String name, String instruction, String outputKey) {
        AiAgentConfigTableVO.Module.Agent stage = new AiAgentConfigTableVO.Module.Agent();
        stage.setName(name);
        stage.setInstruction(instruction);
        stage.setOutputKey(outputKey);
        return stage;
    }

    private static AiAgentConfigTableVO.Module.AgentWorkflow workflow(String type, String name,
                                                                       int iterations, String... children) {
        AiAgentConfigTableVO.Module.AgentWorkflow workflow = new AiAgentConfigTableVO.Module.AgentWorkflow();
        workflow.setType(type);
        workflow.setName(name);
        workflow.setMaxIterations(iterations);
        workflow.setSubAgents(List.of(children));
        return workflow;
    }

    private record Call(String prompt, int userMessages) {
    }

    private static final class FakeModels implements PaiCliModelFactory {
        private final BiFunction<String, Integer, String> response;
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        private FakeModels(BiFunction<String, Integer, String> response) {
            this.response = response;
        }

        @Override
        public LlmClient create(ModelSettings settings) {
            return new LlmClient() {
                @Override
                public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                    return chat(messages, tools, StreamListener.NO_OP);
                }

                @Override
                public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                    String prompt = messages.get(0).content();
                    int turns = (int) messages.stream().filter(message -> "user".equals(message.role())).count();
                    calls.add(new Call(prompt, turns));
                    String content = response.apply(prompt, turns);
                    listener.onContentDelta(content);
                    return new ChatResponse("assistant", content, List.of(), 1, 1);
                }

                @Override
                public String getModelName() {
                    return "fake";
                }

                @Override
                public String getProviderName() {
                    return "fake";
                }
            };
        }
    }
}
