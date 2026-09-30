# ADR-002 MyLogPlugin 流式日志路由采用每请求实例捕获 requestId，而非 ThreadLocal

- 状态：已被 ADR-008 取代（以下记录迁移前 ADK 实现）
- 日期：2026-03
- 关联任务：`docs/tasks/DONE/TASK-003-命令执行交互审批.md`（`CommandExecutionContextHolder` 与同线程执行链验证来源）
- 关联代码：`data-visualizer-domain/.../plugin/MyLogPlugin.java`、`ChatService.createRequestRunner`

## 背景

流式对话（`chat_stream`）需要把 Agent 运行期日志（run/agent/model/tool 四类事件）实时推送到发起请求的浏览器。`MyLogPlugin` 继承 ADK `LoggingPlugin`，在全部生命周期回调点（`beforeRunCallback`、`onEventCallback`、`beforeModelCallback`、`afterToolCallback`、`onModelErrorCallback` 等）调用 `AgentStreamBridge.publishLog(requestId, ...)`，因此**每个插件实例必须知道自己属于哪个 requestId**。

当前实现：`ChatService.createRequestRunner` 为每次流式请求 new 一个 `MyLogPlugin(requestId, agentStreamBridge)`，复制插件列表构造请求专属 Runner。requestId 作为实例不可变字段被构造器捕获。

TASK-003 引入了 `CommandExecutionContextHolder`（ThreadLocal），并在**当前默认串行工作流**下通过运行日志验证了同线程执行链：`CompletableFuture.runAsync → ADK → Spring AI Tool → ShellExecutor` 全程同一线程。由此产生一个自然的疑问：`MyLogPlugin` 是否也可以改为无状态单例，通过同一个 ThreadLocal 读取 requestId 路由日志，从而省去每请求创建实例和复制插件列表？

## 决策

**不采用 ThreadLocal 路由，保留"每请求创建独立插件实例、构造器捕获 requestId"的方案。** 理由：

1. **插件回调的线程保证比 ShellExecutor 更脆弱。** TASK-003 的同线程验证范围仅限"当前串行工作流、当前执行方式"。而 `MyLogPlugin` 的回调点覆盖 ADK 全部生命周期，挂在 RxJava `Maybe`/`Flowable` 链上。一旦：切换到 YAML 中已配置的 parallel 工作流（并行分支在不同线程执行子 Agent）、ADK 升级后模型流式回调切换到 IO Scheduler、或未来改用虚拟线程/异步 Tool——ThreadLocal 读到 `null`，日志静默丢失，且难以排查。实例捕获 requestId 与线程模型完全解耦，对上述所有变化免疫；并行工作流下各分支日志仍正确路由到同一 requestId。

2. **失败模式不同：ShellExecutor 丢上下文是设计行为，日志丢上下文是数据丢失。** TASK-003 对 `ShellExecutor` 读不到 ThreadLocal 的情况定义了显式安全失败路径（返回 `forbidden`、不等待、不执行），这是有意的业务语义。而 `MyLogPlugin` 丢上下文只能静默吞掉日志——没有任何补偿。日志恰恰是排查"流式输出为什么不对"的依据，出问题时会同时失去诊断工具。

3. **这是文档化的刻意设计决策。** `architecture.md` 与 `business.md` 的 AI 开发注意事项均要求：修改 `MyLogPlugin`、`ChatService.createRequestRunner` 或插件列表时，保留"每个流式请求创建独立日志插件实例"的隔离行为，避免并发请求日志串流。

4. **收益太小。** ThreadLocal 方案的收益仅是省去每次请求 new 一个小对象 + 复制插件列表，成本相对一次 LLM 调用可忽略；为微小收益引入线程模型耦合不划算。

## 为什么这么做

| 维度 | 每请求实例捕获（已选） | ThreadLocal 路由（被否决） |
| --- | --- | --- |
| 线程模型依赖 | 无（与线程完全解耦） | 依赖"所有回调同线程"，仅对当前串行工作流验证过 |
| 并行/loop 工作流 | 正常路由 | 分支线程读不到上下文，日志丢失 |
| ADK/RxJava 升级 | 不受影响 | Scheduler 变化即失效 |
| 失败表现 | 无失败模式 | 静默丢日志，难排查 |
| 对象开销 | 每请求一个小对象（可忽略） | 单例，无开销 |
| 与 CommandExecutionContext 的关系 | 无耦合 | 可复用，但会把命令审批上下文的线程验证前提扩大到日志链路 |

## 被否决的替代方案

| 方案 | 否决原因 |
| --- | --- |
| 无状态单例 `MyLogPlugin` + `CommandExecutionContextHolder` 读取 requestId | 依赖未验证的线程传播前提（插件回调点远多于工具调用点）；并行分支/Scheduler 切换下日志静默丢失；把命令审批上下文的线程验证范围隐式扩大到日志链路。 |
| 复用现有 `@Service("myLogPlugin")` 无参单例 Bean | 该 Bean 的 `requestId` 为 null，`emitLog` 静默丢弃全部日志；仅作为 Spring 容器兜底存在，不能承担流式路由职责。 |
| 让 ADK `InvocationContext`/会话 state 携带 requestId | 与线程彻底解耦的正解方向，但需要侵入 ADK 装配或 Runner 构建，改造面大；留作未来插件数量增多时的演进选项。 |
| 每请求完整重建 Runner（而非复制插件列表） | 成本更高且无额外收益；当前"复制插件列表 + 替换日志插件"已足够隔离。 |

## 后果

- 每次流式请求产生一个 `MyLogPlugin` 实例和一份插件列表副本：可接受的固定小开销，换取线程模型完全解耦。
- `MyLogPlugin` 保持两个构造器：请求实例（requestId + bridge）与 Spring 无参兜底 Bean（null 字段，静默丢弃日志）。修改任一构造器的字段语义时必须同步检查 `ChatService.createRequestRunner`。
- `CommandExecutionContextHolder` 的 ThreadLocal 语义边界保持不变：仅服务于 TASK-003 命令审批与 `ShellExecutor` 自身的执行路径（ADR-004 后额外用于解析本地 Shell 作用域 key），其"同线程执行链已验证"的前提**不得**被其他组件（如 `MyLogPlugin`）隐式引用为本 ADR 的依据。
- 若未来确需统一上下文（如插件数量增多、复制成本显现），演进顺序应为：(1) 用日志验证全部回调点（尤其 parallel 工作流与模型流式回调）均在执行线程上；(2) 优先评估 `InvocationContext` 状态注入等与线程解耦的方案；(3) 任何改动必须为"无上下文"定义显式降级策略并记录日志，不得静默吞日志。
- 本 ADR 生效期间，若切换默认 Runner 入口到 parallel/loop 工作流，无需为日志路由做任何额外改造（实例捕获天然支持）；但需按 TASK-003 风险 1 重新验证命令审批的 ThreadLocal 传播。
