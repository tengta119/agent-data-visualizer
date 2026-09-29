# PaiCLI embedded kernel

This module contains a source snapshot of the PaiCLI ReAct runtime. It is part of
the data-visualizer Maven reactor and does not require a PaiCLI process, local JAR,
or `systemPath` dependency. `UPSTREAM-SOURCES.txt` records the source commit and
each copied file. The snapshot contains 147 copied Java files and 22 resources;
five Java files under `com.paicli.embed`, `AgentRunException`, and
`ConfiguredOpenAiClient` are local additions.

The copied sources changed for embedding are `Agent`, `ToolRegistry`,
`LongTermMemory`, `MemoryManager`, `CancellationContext`, `LlmClient`, and
`AbstractOpenAiCompatibleClient`. The changes add a host-owned system
instruction, an empty-by-default tool registry, memory without disk persistence,
typed model and cancellation errors, a thread-bound cancellation token,
configurable model transport, and credential-safe model errors.
CLI, TUI, WeChat, Runtime HTTP API, evaluation entry points,
and `logback.xml` were excluded.

## Embedded host API

```java
EmbeddedAgent agent = new EmbeddedAgent(llmClient, stageSystemInstruction);
agent.registerMcpTool(descriptor, jsonArguments -> hostTool.apply(jsonArguments));
EmbeddedTurnResult result = agent.run(stageInput, submittedUserInput,
        event -> handleEvent(event));
```

`llmClient` implements `LlmClient`. The host supplies a trusted instruction for
each stage and explicitly registers every allowed tool before the first turn.
`availableTools()` starts empty. `run` preserves conversation history across
turns on the same instance and serializes those turns. Create separate instances
for independent sessions or stages. `setSystemInstruction()` refreshes a
stage's trusted instruction before a later turn without discarding history.
`submittedUserInput` is the
original user text used by `TurnToolPolicy` for URL and web restrictions; the
expanded `stageInput` is sent to the model. Events carry content deltas, tool
names, or status phases. Model I/O, cancellation, and other execution failures
are distinguished by `EmbeddedTurnException.Kind`. `cancel()` may be called from
another thread and cancels the active token and in-flight model request.

The embedded entry point uses in-memory long-term memory and disables tool
result file offloading. It never starts a terminal UI or network listener.
Built-in Shell, file, web, and browser tools are not registered. The host remains
responsible for access control and side effects of any tool it registers.

Jackson and SLF4J use the versions managed by the root Spring Boot parent;
OkHttp 4.12.0 and the other standalone libraries use explicit versions in this
module POM. No `logback.xml` is packaged, so the application owns logging.
`prompts/` and `skills/` resources retain their upstream classpath paths; the
embedded prompt path uses the supplied system instruction rather than loading
PaiCLI's default prompt. The domain module now uses this API in an internal
workflow service. The running HTTP path still uses ADK; TASK-008 will switch it.

Per the repository's `AGENTS.md`, build and test commands are run manually by
the developer. Focused fake-model tests are provided in `EmbeddedAgentTest`.
