# PaiCLI workflow handoff for TASK-008

Inject `IPaiCliWorkflowService` in the HTTP adapter. Install a fully bound
`AiAgentAutoConfigProperties` at startup and on configuration updates:

```java
long version = workflowService.install(config);
String sessionId = workflowService.createSession(agentId, userId);
PaiCliWorkflowResult result = workflowService.run(agentId, userId, sessionId,
        userText, event -> publishStreamEvent(event));
workflowService.cancel(agentId, userId, sessionId);
```

`currentConfiguration()` and `listAgents()` read the same published snapshot.
`run()` returns the final stage answer and output-key values. The event callback
reports stage start/completion, model content deltas, tool names, and status.
`PaiCliWorkflowException.Reason` distinguishes configuration, agent, session
ownership, expiry, and workflow-state errors. `EmbeddedTurnException.Kind`
distinguishes model I/O, cancellation, and execution failures.

The default YAML's `agentId=100003`, `sequential_draw_process`, and
`analysis_result → draft_diagram → final_result` are covered by a fake-model
test. The `resource: agent/skills` setting loads the four drawing skill
documents. The known local Shell declaration is validated but **no Shell tool
is registered yet**. TASK-008 must register its restricted adapter and map
events, errors, cancellation, and HTTP/admin updates to this service. Unknown
MCP servers, stdio/SSE tools, directory Skills, and runner plugins outside the
two existing names fail configuration validation.

The OpenAI-compatible model client uses the configured base URL, completions
path, API key, and model. It owns a separate OkHttp dispatcher per stage so
canceling one session does not cancel another session's transport calls. Model
error bodies are not exposed through the host-facing exception chain.

The existing ADK HTTP execution path remains active until TASK-008 switches it.
