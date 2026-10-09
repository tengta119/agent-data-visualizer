package com.paicli.embed;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentRunException;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultOffloader;
import com.paicli.tool.ToolOutput;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** In-process PaiCLI ReAct entry point with an explicit prompt and tool boundary. */
public final class EmbeddedAgent implements AutoCloseable {
    private final LlmClient llmClient;
    private final ToolRegistry tools;
    private final Agent agent;
    private final AtomicReference<CancellationToken> currentRun = new AtomicReference<>();
    private final AtomicBoolean pendingCancel = new AtomicBoolean();
    private boolean started;
    private final List<AutoCloseable> closeables = new java.util.ArrayList<>();

    public EmbeddedAgent(LlmClient llmClient, String trustedSystemInstruction) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        if (trustedSystemInstruction == null || trustedSystemInstruction.isBlank()) {
            throw new IllegalArgumentException("trustedSystemInstruction must not be blank");
        }
        this.tools = ToolRegistry.restricted();
        this.tools.setToolResultOffloader(new ToolResultOffloader(
                Path.of(System.getProperty("user.dir")), false, 32_000));
        this.agent = new Agent(llmClient, tools,
                new MemoryManager(llmClient, LongTermMemory.inMemory()), trustedSystemInstruction);
        this.agent.setReturnFinalResponseWhenStreamed(true);
        this.agent.setFailOnToolError(true);
    }

    /** Registers a tool explicitly; no PaiCLI built-in tool is registered by default. */
    public synchronized void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        if (started) {
            throw new IllegalStateException("Cannot change tools after the first turn");
        }
        tools.registerMcpTool(descriptor, invoker);
    }

    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor,
                                                    Function<String, ToolOutput> invoker) {
        if (started) {
            throw new IllegalStateException("Cannot change tools after the first turn");
        }
        tools.registerMcpToolOutput(descriptor, invoker);
    }

    public List<LlmClient.Tool> availableTools() {
        return tools.getToolDefinitions();
    }

    /** Uses the same restricted registry and result typing as model-initiated calls. */
    public ToolOutput invokeTool(String name, String argumentsJson) {
        return tools.executeToolOutput(name, argumentsJson);
    }

    public synchronized void onClose(AutoCloseable resource) {
        if (started) {
            throw new IllegalStateException("Cannot add resources after the first turn");
        }
        closeables.add(Objects.requireNonNull(resource));
    }

    /** Refreshes the trusted stage instruction while retaining this session's conversation history. */
    public synchronized void setSystemInstruction(String instruction) {
        agent.setEmbeddedSystemInstruction(instruction);
    }

    /**
     * 复用同一 Agent 的会话历史，并串行执行 turn。
     * 每次执行单独绑定取消令牌；结束时解除绑定并关闭本次事件渲染器。
     */
    public synchronized EmbeddedTurnResult run(String input, String submittedUserInput, Consumer<EmbeddedEvent> listener) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        started = true;
        llmClient.prepareForRun();
        CancellationToken token = new CancellationToken();
        currentRun.set(token);
        if (pendingCancel.getAndSet(false)) {
            token.cancel();
            llmClient.cancelInFlightCalls();
        }
        EmbeddedRenderer renderer = new EmbeddedRenderer(listener);
        agent.setRenderer(renderer);
        try (CancellationContext.Scope ignored = CancellationContext.bind(token)) {
            String result = agent.runExplicitTask(input, submittedUserInput == null ? input : submittedUserInput);
            return new EmbeddedTurnResult(result);
        } catch (AgentRunException error) {
            EmbeddedTurnException.Kind kind = switch (error.reason()) {
                case CANCELLED -> EmbeddedTurnException.Kind.CANCELLED;
                case TOOL -> EmbeddedTurnException.Kind.TOOL;
                case MODEL_IO -> EmbeddedTurnException.Kind.MODEL_IO;
            };
            throw new EmbeddedTurnException(kind, error.getMessage(), error);
        } catch (RuntimeException error) {
            throw new EmbeddedTurnException(EmbeddedTurnException.Kind.EXECUTION,
                    "Embedded Agent execution failed", error);
        } finally {
            currentRun.compareAndSet(token, null);
            renderer.close();
        }
    }

    public EmbeddedTurnResult run(String input) {
        return run(input, input, null);
    }

    /**
     * 允许其他线程取消正在等待的模型调用。
     * 若取消早于本次令牌安装，pendingCancel 会让紧接着开始的 run 立即收到取消信号。
     */
    public void cancel() {
        CancellationToken token = currentRun.get();
        if (token != null) {
            token.cancel();
            llmClient.cancelInFlightCalls();
        } else {
            pendingCancel.set(true);
            token = currentRun.get();
            if (token != null) {
                pendingCancel.set(false);
                token.cancel();
                llmClient.cancelInFlightCalls();
            }
        }
    }

    @Override
    public synchronized void close() {
        cancel();
        for (AutoCloseable resource : closeables) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // Closing one connection must not leak the remaining connections.
            }
        }
        closeables.clear();
    }
}
