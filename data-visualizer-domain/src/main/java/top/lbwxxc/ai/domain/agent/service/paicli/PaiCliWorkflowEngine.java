package top.lbwxxc.ai.domain.agent.service.paicli;

import com.paicli.embed.EmbeddedAgent;
import com.paicli.embed.EmbeddedEvent;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliConfigCompiler.AgentDefinition;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliConfigCompiler.StageSpec;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliConfigCompiler.WorkflowSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes the configured graph without sharing mutable output state between parallel branches. */
final class PaiCliWorkflowEngine {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");
    private final PaiCliModelFactory models;
    private final Executor parallelExecutor;

    PaiCliWorkflowEngine(PaiCliModelFactory models, Executor parallelExecutor) {
        this.models = models;
        this.parallelExecutor = parallelExecutor;
    }

    PaiCliWorkflowResult run(AgentDefinition definition, String input, Map<String, EmbeddedAgent> stageAgents,
                             Set<EmbeddedAgent> activeAgents, Consumer<PaiCliWorkflowEvent> listener) {
        Map<String, String> state = new LinkedHashMap<>();
        Object eventLock = new Object();
        String last = execute(definition.entry(), "root", definition, input, state,
                stageAgents, activeAgents, listener, eventLock);
        return new PaiCliWorkflowResult(last, state);
    }

    private String execute(String name, String path, AgentDefinition definition, String input,
                           Map<String, String> state, Map<String, EmbeddedAgent> stageAgents,
                           Set<EmbeddedAgent> activeAgents, Consumer<PaiCliWorkflowEvent> listener,
                           Object eventLock) {
        StageSpec stage = definition.stages().get(name);
        if (stage != null) {
            String instruction = interpolate(stage.instruction(), state) + stage.skills();
            EmbeddedAgent agent = stageAgents.computeIfAbsent(path,
                    ignored -> new EmbeddedAgent(models.create(definition.model()), instruction));
            agent.setSystemInstruction(instruction);
            activeAgents.add(agent);
            try {
                emit(listener, eventLock, new PaiCliWorkflowEvent(
                        PaiCliWorkflowEvent.Kind.STAGE_STARTED, name, ""));
                String result = agent.run(input, input, event -> forward(event, name, listener, eventLock)).content();
                state.put(stage.outputKey(), result);
                emit(listener, eventLock, new PaiCliWorkflowEvent(
                        PaiCliWorkflowEvent.Kind.STAGE_COMPLETED, name, result));
                return result;
            } finally {
                activeAgents.remove(agent);
            }
        }

        WorkflowSpec workflow = definition.workflows().get(name);
        if (workflow == null) {
            throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.WORKFLOW_STATE,
                    "Unknown workflow node: " + name);
        }
        return switch (workflow.type()) {
            case SEQUENTIAL -> runSequence(workflow, path, definition, input, state,
                    stageAgents, activeAgents, listener, eventLock);
            case LOOP -> runLoop(workflow, path, definition, input, state,
                    stageAgents, activeAgents, listener, eventLock);
            case PARALLEL -> runParallel(workflow, path, definition, input, state,
                    stageAgents, activeAgents, listener, eventLock);
        };
    }

    private String runSequence(WorkflowSpec workflow, String path, AgentDefinition definition, String input,
                               Map<String, String> state, Map<String, EmbeddedAgent> stageAgents,
                               Set<EmbeddedAgent> activeAgents, Consumer<PaiCliWorkflowEvent> listener,
                               Object eventLock) {
        String last = "";
        for (int i = 0; i < workflow.children().size(); i++) {
            last = execute(workflow.children().get(i), path + "/" + i, definition, input, state,
                    stageAgents, activeAgents, listener, eventLock);
        }
        return last;
    }

    private String runLoop(WorkflowSpec workflow, String path, AgentDefinition definition, String input,
                           Map<String, String> state, Map<String, EmbeddedAgent> stageAgents,
                           Set<EmbeddedAgent> activeAgents, Consumer<PaiCliWorkflowEvent> listener,
                           Object eventLock) {
        String last = "";
        for (int iteration = 0; iteration < workflow.iterations(); iteration++) {
            for (int i = 0; i < workflow.children().size(); i++) {
                last = execute(workflow.children().get(i), path + "/" + i, definition, input, state,
                        stageAgents, activeAgents, listener, eventLock);
            }
        }
        return last;
    }

    private String runParallel(WorkflowSpec workflow, String path, AgentDefinition definition, String input,
                               Map<String, String> state, Map<String, EmbeddedAgent> stageAgents,
                               Set<EmbeddedAgent> activeAgents, Consumer<PaiCliWorkflowEvent> listener,
                               Object eventLock) {
        Map<String, String> base = new LinkedHashMap<>(state);
        List<CompletableFuture<BranchResult>> futures = new ArrayList<>();
        AtomicBoolean aborted = new AtomicBoolean();
        for (int i = 0; i < workflow.children().size(); i++) {
            String child = workflow.children().get(i);
            String childPath = path + "/" + i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (aborted.get()) {
                    return new BranchResult("", new LinkedHashMap<>(base));
                }
                Map<String, String> branch = new LinkedHashMap<>(base);
                String result = execute(child, childPath, definition, input, branch,
                        stageAgents, activeAgents, listener, eventLock);
                return new BranchResult(result, branch);
            }, parallelExecutor));
        }
        List<BranchResult> results = new ArrayList<>();
        try {
            for (CompletableFuture<BranchResult> future : futures) {
                results.add(future.join());
            }
        } catch (CompletionException error) {
            aborted.set(true);
            activeAgents.forEach(EmbeddedAgent::cancel);
            for (CompletableFuture<BranchResult> future : futures) {
                try {
                    future.join();
                } catch (CompletionException ignored) {
                    // Drain every branch before the session lock can be released.
                }
            }
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw error;
        }
        String last = "";
        for (BranchResult result : results) {
            for (Map.Entry<String, String> value : result.state().entrySet()) {
                if (!base.containsKey(value.getKey()) || !base.get(value.getKey()).equals(value.getValue())) {
                    state.put(value.getKey(), value.getValue());
                }
            }
            last = result.content();
        }
        return last;
    }

    private String interpolate(String template, Map<String, String> state) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String value = state.get(matcher.group(1));
            if (value == null) {
                throw new PaiCliWorkflowException(PaiCliWorkflowException.Reason.WORKFLOW_STATE,
                        "Missing workflow output: " + matcher.group(1));
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private void forward(EmbeddedEvent event, String stage, Consumer<PaiCliWorkflowEvent> listener,
                         Object eventLock) {
        PaiCliWorkflowEvent.Kind kind = switch (event.kind()) {
            case CONTENT_DELTA -> PaiCliWorkflowEvent.Kind.CONTENT_DELTA;
            case TOOL_CALL -> PaiCliWorkflowEvent.Kind.TOOL_CALL;
            case STATUS -> PaiCliWorkflowEvent.Kind.STATUS;
        };
        emit(listener, eventLock, new PaiCliWorkflowEvent(kind, stage, event.content()));
    }

    private void emit(Consumer<PaiCliWorkflowEvent> listener, Object eventLock, PaiCliWorkflowEvent event) {
        if (listener != null) {
            synchronized (eventLock) {
                listener.accept(event);
            }
        }
    }

    private record BranchResult(String content, Map<String, String> state) {
    }
}
