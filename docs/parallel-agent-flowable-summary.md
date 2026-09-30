# ParallelAgent、Flowable 与工作流数据流转总结

本文整理了本项目中 Google ADK `ParallelAgent`、RxJava `Flowable<Event>`、线程/并发关系，以及 Agent 工作流数据传递方式的分析结论。分析基于项目当前依赖的 Google ADK `0.5.0` 和现有实现。

## 1. 相关组件各自负责什么

| 组件 | 责任 | 是否直接创建线程 |
| --- | --- | --- |
| `ParallelAgentNode` | 根据 YAML 配置装配 `ParallelAgent` | 否 |
| `ParallelAgent` | 启动多个子 Agent 的事件流并将其合并 | 否 |
| `SequentialAgent` | 按配置顺序依次运行子 Agent | 否 |
| `Runner` / `InMemoryRunner` | 创建调用上下文、维护 Session、驱动 Agent 运行、持久化事件 | 未在此流程中直接创建线程 |
| `Flowable<Event>` | 表示执行过程中陆续产生的事件流 | 不等于线程 |
| `CompletableFuture.runAsync` | 将整个流式 HTTP 请求处理放进异步任务 | 是，使用默认公共线程池（未传入 executor 时） |

项目中的 `ParallelAgentNode` 只做“装配”：从 `subAgents` 配置取得已经构造好的 `BaseAgent`，创建并注册 `ParallelAgent`。

```java
ParallelAgent parallelAgent = ParallelAgent.builder()
    .name(currentAgentWorkflow.getName())
    .description(currentAgentWorkflow.getDescription())
    .subAgents(subAgents)
    .build();
```

它不会在收到用户消息时为每个子 Agent 写出 `new Thread(...)`、`ExecutorService.submit(...)` 或 `CompletableFuture.runAsync(...)`。

## 2. 一条用户消息如何进入工作流

用户请求最终会进入 `ChatService`，由 Runner 创建一个执行事件流：

```java
Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);
```

大致调用链：

```text
HTTP 请求
  → ChatService.handleMessageStream(...)
  → Runner.runAsync(userId, sessionId, userMessage)
  → 根 Agent.runAsync(invocationContext)
  → SequentialAgent / ParallelAgent / LlmAgent 等具体节点
  → Flowable<Event> 事件流
  → SSE 推送给前端（流式接口）或 blockingForEach 汇总（普通接口）
```

流式接口的 Controller 额外使用了：

```java
CompletableFuture.runAsync(() -> {
    chatService.handleMessageStream(...).subscribe(...);
});
```

它的目的，是使 HTTP 请求线程可以返回 `ResponseBodyEmitter`，而不会等待整个模型生成结束。这个异步任务以“一个流式 HTTP 请求”为粒度，不是“每个并行子 Agent 一个线程”。

## 3. 什么是 Flowable\<Event>

`Flowable<T>` 是 RxJava 3 的响应式数据流类型，`Flowable<Event>` 就是“在未来陆续产生多个 ADK Event 的事件管道”。

`Event` 可以表达模型输出片段、工具调用、工具结果、最终回答等。它不是一次性返回的 `List<Event>`：获得 `Flowable` 时，执行还可能尚未发生或尚未完成；订阅后，消费者会陆续收到事件。

```java
events.subscribe(
    event -> { /* onNext：每来一条 Event 调用一次 */ },
    error -> { /* onError：失败时调用一次，之后结束 */ },
    () -> { /* onComplete：正常完成时调用一次 */ }
);
```

一个流的终止形式只能是二选一：

```text
onNext(event1) → onNext(event2) → ... → onComplete()
```

或：

```text
onNext(event1) → onNext(event2) → ... → onError(error)
```

`Flowable` 还具备背压（backpressure）语义：当上游产出事件快于下游处理速度时，响应式链路可以按需求数量、缓存和策略进行协调。是否能够把限速一路传递到模型服务端，仍取决于 ADK 和底层 HTTP 客户端。

### Flowable 不等于自动切线程

以下代码不保证订阅回调在另一个线程执行：

```java
flowable.subscribe(event -> handle(event));
doSomethingElse();
```

若上游是同步的且没有指定调度器，`handle(event)` 通常会在订阅线程运行，`doSomethingElse()` 会在流同步结束后才继续。

线程切换通常来自：

- `subscribeOn(Scheduler)`：安排订阅与上游工作所在线程；
- `observeOn(Scheduler)`：安排后续操作符及订阅回调所在线程；
- 上游本身的异步实现，例如异步 HTTP 客户端在 I/O 回调线程投递事件；
- 项目外层显式的 `CompletableFuture.runAsync(...)`。

因此应区分：

```text
异步事件流：可以稍后、分多次送达数据。
多线程：代码确实被调度到其他线程运行。
并行：多个任务在时间上重叠推进，可能使用多个线程，也可能由异步 I/O 驱动。
```

## 4. ParallelAgent 的实际实现和“并发”含义

ADK 0.5.0 的 `ParallelAgent.runAsyncImpl` 核心逻辑为：

```java
List<Flowable<Event>> agentFlowables = new ArrayList<>();
for (BaseAgent subAgent : subAgents()) {
    agentFlowables.add(subAgent.runAsync(invocationContext));
}
return Flowable.merge(agentFlowables);
```

它对所有子 Agent 创建事件流，并用 `Flowable.merge(...)` 合并。订阅合并后的总流时，A、B、C 子流都会被订阅；不会等待 A 完成后才运行 B，也不会等待 B 后才运行 C。

```text
用户消息
  ├── Agent A → Flowable<Event> ─┐
  ├── Agent B → Flowable<Event> ─┼── Flowable.merge → 总 Flowable<Event>
  └── Agent C → Flowable<Event> ─┘
```

时序上可能是：

```text
A: ── A1 ───── A2 ───────── 完成
B: ───── B1 ───── B2 ────── 完成
C: ─ C1 ───────────── C2 ── 完成

合并流：C1 → A1 → B1 → A2 → B2 → C2 → 完成
```

这表示工作流层面的**并发执行语义**：多个子任务没有前后依赖，可以同时发起模型或工具调用，等待阶段能够重叠。总体耗时通常更接近最慢子任务的耗时，而不是所有子任务耗时之和。

但 `ParallelAgent` 本身没有线程池、`subscribeOn` 或 `observeOn`，所以它不承诺“每个子 Agent 创建一个新线程”。是否得到实际线程级并行，要看子 Agent 的底层实现：

| 子 Agent 的底层工作 | 常见实际效果 |
| --- | --- |
| 异步 LLM HTTP 调用 / 异步工具调用 | 多个请求可同时处于网络等待中，效果接近并行 |
| 显式调度到不同线程池 | 可在多个工作线程上同时运行 |
| 同步阻塞代码且无 Scheduler 切换 | 可能在同一订阅线程轮流运行，未必有线程级并行 |
| CPU 密集任务 | 只有调度到多线程资源时才能使用多核并行 |

另外，`merge` 只负责合流：它不会自动排序、拼接答案、选择最佳结果，也不把一个 Agent 的输出自动提供给另一个 Agent。默认情况下，子流中任一流发生错误可使合并流失败。

## 5. 合并后事件如何处理

子 Agent 内部可以完全不同：不同 prompt、模型、工具和任务。合并后，每个 Event 仍保留来源信息，例如 `event.author()` 和 `event.branch()`，所以可以识别其来自哪个 Agent。

项目当前流式 Controller 对合并后的所有事件采用同一套消费逻辑：取得 `event.stringifyContent()`，然后发布给 SSE。它不会按 `author` 区分或分类存储。因此多个 Agent 的流式文本可能按到达时间交错显示。

若前端需按角色展示，应将 `event.author()`、`event.branch()` 一并放入 SSE DTO，并在前端按来源分组渲染。若需最终统一答案，应另设一个串行后续的汇总/评审 Agent。

## 6. SequentialAgent 与 ParallelAgent 的关系

`SequentialAgent` 的核心实现：

```java
Flowable.fromIterable(subAgents())
    .concatMap(subAgent -> subAgent.runAsync(invocationContext));
```

`concatMap` 的含义是：当前子 Agent 的事件流必须完整结束，才开始订阅下一个。因此可以将并行节点嵌入串行节点，获得“前置分析 → 并行候选生成 → 后置汇总”的结构：

```text
SequentialAgent（根工作流）
  ├── agent_analyst
  ├── parallel_generation
  │     ├── generator_A
  │     ├── generator_B
  │     └── generator_C
  └── agent_reviewer
```

执行顺序：

```text
agent_analyst 完成
  → parallel_generation 的所有子 Agent 并发执行
  → 所有并行子流均完成
  → agent_reviewer 开始执行
```

## 7. Agent 之间的数据如何流转

工作流节点之间不会直接将“上一个 Agent 的字符串返回值”作为 Java 方法参数交给下一个 Agent。主要的数据通道是：

1. 同一个 `Session` 的事件历史（`Session.events`）；
2. 同一个 `Session` 的状态（`Session.state`）。

Runner 处理用户消息时：

1. 为本次请求建立 `InvocationContext`，其中带有当前 Session、用户消息、`invocationId` 等；
2. 将用户消息包装成 author 为 `user` 的 Event，追加到 Session；
3. 运行根 Agent；
4. 每个 Agent 输出的 Event 都会由 Runner 追加到 Session；
5. Event 上的 `stateDelta` 合并到 Session state；
6. 后续 Agent 按当前 Session state 解析 instruction 中的 `{变量名}`。

每个子 Agent 运行时会通过 `InvocationContext.copyOf(parentContext)` 获得派生上下文。该操作是浅拷贝：子节点的 `agent` 和 `branch` 会变化，但 Session 仍是同一个 Session。因此，串行节点可以读到前序节点写入的状态；并行节点也会共享同一份 state。

### outputKey 是显式、可靠的数据交接方式

`LlmAgent` 在产生最终响应 Event 时，若配置了 `outputKey`，会将最终文本写入事件的 state delta：

```java
event.actions().stateDelta().put(outputKey, output);
```

Runner 将事件追加到 Session 后，该 delta 成为 Session state 的一部分。后续 Agent 可以在 instruction 中引用：

```text
{analysis_result}
{candidate_a}
{candidate_b}
```

这比依赖对话历史中的自然语言文本更适合作为结构化的工作流数据交接。

## 8. `agent_analyst → parallel_generation → agent_reviewer` 示例

建议的设计：

```text
agent_analyst
  输出键：analysis_result

parallel_generation
  ├── agent_generator_a，输出键：candidate_a
  ├── agent_generator_b，输出键：candidate_b
  └── agent_generator_c，输出键：candidate_c

agent_reviewer
  读取：{analysis_result}、{candidate_a}、{candidate_b}、{candidate_c}
  输出键：final_result
```

状态变化：

```text
初始：
Session.state = {}

agent_analyst 完成后：
Session.state = {
  analysis_result: "用户需求的分析结果"
}

parallel_generation 全部完成后：
Session.state = {
  analysis_result: "...",
  candidate_a: "方案 A",
  candidate_b: "方案 B",
  candidate_c: "方案 C"
}

agent_reviewer 完成后：
Session.state = {
  analysis_result: "...",
  candidate_a: "...",
  candidate_b: "...",
  candidate_c: "...",
  final_result: "评审、选择或融合后的最终结果"
}
```

reviewer 的指令应显式引用所有候选结果，例如：

```text
请基于需求分析 {analysis_result}，比较下列候选方案：
- 候选 A：{candidate_a}
- 候选 B：{candidate_b}
- 候选 C：{candidate_c}

选择或融合最佳方案，并输出最终结果。
```

## 9. 当前项目 YAML 的实际情况与风险

当前配置文件 `data-visualizer-app/src/main/resources/agent/data-visualizer-agent.yml` 中：

```yaml
parallel_generation:
  sub-agents:
    - agent_drawer
    - agent_drawer
```

两个条目引用的是同一个 `agent_drawer` 配置，其 `output-key` 都是 `draft_diagram`。这会产生并发覆盖：

```text
drawer 调用 #1 完成 → state["draft_diagram"] = 方案 1
drawer 调用 #2 完成 → state["draft_diagram"] = 方案 2
```

最终 state 中只保留最后一次写入的值。最后完成者由模型和网络响应时间决定，不能依赖 YAML 中的声明顺序。因此 reviewer 无法稳定地读取全部候选方案。

此外，当前 Runner 的入口是：

```yaml
runner:
  agent-name: sequential_draw_process
```

而 `sequential_draw_process` 当前子节点为：

```text
agent_analyst → agent_drawer → agent_reviewer
```

它没有包含 `parallel_generation`。因此，当前默认用户请求不会执行已经声明的并行工作流。

若要启用“分析 → 并行生成 → 审查”的模式，需要：

1. 让根 `SequentialAgent` 的 `sub-agents` 包含 `parallel_generation`；
2. 为每条并行分支设置独立 Agent 名称、差异化 instruction（例如不同绘图策略）和独立 `outputKey`；
3. 在 reviewer instruction 中显式引用所有候选输出键；
4. 避免多个并发子 Agent 写入同一个 state key，除非最后完成者覆盖前者正是预期行为。

## 10. 最终结论

- `ParallelAgentNode` 负责装配，`ParallelAgent` 负责并行编排，二者都不直接按子 Agent 创建线程。
- `Flowable<Event>` 是异步事件流抽象，不自动意味着多线程。
- `ParallelAgent` 的“并行”是：所有子 Agent 流会同时被订阅并合流，不等待前一个子 Agent 完成；实际线程级并行由 Scheduler、异步 HTTP 和底层实现决定。
- `Flowable.merge` 只合并事件，不排序、不自动汇总、不自动传递一个子 Agent 的结果给另一个子 Agent。
- `SequentialAgent` 使用 `concatMap`，能够确保前一个步骤完成后再执行下一步骤。
- 工作流中的可靠数据传递应使用 `outputKey → Event.stateDelta → Session.state → {stateKey}`。
- 并行分支必须使用独立的 `outputKey`，后置 reviewer/aggregator 再读取并汇总这些结果。
