# ADR-004 本地 Shell 作用域改为「每请求一个独立短生命周期进程」

- 状态：已接受
- 日期：2026-09
- 关联任务：`docs/tasks/DOING/TASK-005-ShellExecutor每次请求独立短生命周期Shell.md`
- 关联代码：`data-visualizer-domain/.../shell/ShellExecutor.java`、`shell/scope/`（`LocalShellScope`、`LocalShellRegistry`、`LocalShellSession`、`LocalShellHandle`、`LocalShellLauncher` 及其进程实现）、`data-visualizer-trigger/.../AgentServiceController.cleanupStream`
- 取代：ADR-003 中"本地 Shell 为进程内单例、超时销毁全局唯一 Shell"的部分（第 1~4 条）

## 背景

ADR-003 把本地命令执行改为"有界单线程执行器 + `Future.get` 超时"，解决了并发写同一 Shell 与挂死命令永久占用 Shell 的问题，但当时保留了一个**进程内单例的长期存活 Shell**（`shellProcessRef`/`writer`/`reader`/`shellName` 都是 `ShellExecutor` 的实例字段）。ADR-003 自己把"每次请求启动独立短生命周期 Shell"列为待评估方向，并注明：

> 每次请求启动独立短生命周期 Shell —— 能同时解决隔离与并发，但每条命令都要支付进程启动成本，且改变 `cd` 状态在单轮对话内不再保持；属于更大的架构改动，另行评估。

单例 Shell 暴露出四类问题：

1. **跨请求状态泄漏。** 长驻 Shell 保留前一请求的 `cd`、环境变量与 shell 变量，串行化只能防止命令交错，不能提供请求级隔离。
2. **生命周期与请求无关。** 正常路径下空转的 `pwsh.exe` 会在整个应用生命周期内存在，任何清理逻辑都不会回收它。
3. **无法在请求结束时释放资源。** 请求结束（`onCompletion`/`onTimeout`/`onError`/断开）与 Shell 是否仍被持有之间没有关联。
4. **超时销毁粒度是"全局唯一 Shell"。** 一次超时会销毁共享 Shell，导致**所有**请求的后续本地命令被迫重建并丢失各自上下文，而不仅仅是触发超时的那次请求。

同时，`AgentServiceController.cleanupStream` 已经是一个幂等、覆盖全部流终止路径的请求生命周期钩子；`CommandExecutionContextHolder` 已经能把 `requestId` 传到 `ShellExecutor`（TASK-003 已验证的同线程执行链）。

## 决策

**保留 ADR-003 的执行模型，把"Shell 实例"从单例字段改为按作用域 key 管理的注册表。**

1. **作用域 key = 当前请求的 `requestId`**，只在 `LocalShellScope.resolve()` 一处解析；禁止在其他位置拼接或推断 key，也禁止用 `sessionId`（会在同一会话的多次请求间复用，等于跨请求共享）或 `agentId:userId` 复合键。
2. **无上下文时使用一次性作用域**：同步 `/api/v1/chat` 没有 `requestId`，此时为该条命令创建一个临时 Shell 并在命令结束后立即销毁；**不**退化为全局共享 Shell，也不按 userId/sessionId 猜测。
3. **按 key 注册表**（`LocalShellRegistry`）：创建只发生在本地 Shell 专用执行线程内，因此不存在并发创建；销毁是"销毁进程 + 原子移除登记"的幂等动作，允许超时线程、请求结束清理线程、执行器线程与 Spring 关闭线程调用。
4. **执行端可替换**（`LocalShellLauncher` + `LocalShellHandle`）：把"启动/读写/销毁进程"与"按作用域管理生命周期"解耦，测试可用内存管道替身断言复用、隔离与销毁，而不依赖真实 Shell。
5. **保留 ADR-003 的全部其它决策**：全局单线程执行器（core=1、max=1、有界队列、`CallerRunsPolicy`）、`Future.get` 超时语义、兜底执行被拒绝返回 `unavailable`、状态码取值集合、脱敏审计。
6. **销毁路径共有五条且共享同一实现**：请求结束（`cleanupStream` → `closeRequestShell(requestId)`）、命令超时、上限淘汰、空闲回收、应用关闭。
7. **数量与时间有界**：`max-concurrent-shells`（默认 8）达上限时按 LRU 淘汰最久未使用的**空闲**会话，无可淘汰项时返回 `unavailable`；`shell-idle-timeout-millis`（默认 20 分钟，与流式 emitter 超时对齐）做机会式清扫，且**只回收"当前空闲且请求已不在 `AgentStreamBridge` 中"的 Shell**，避免打断模型长思考后的后续命令。
8. **只在审批通过、即将写入时才创建 Shell**：策略拒绝、审批拒绝/过期/取消、上下文失效、并发超限、`remote` 命令与 `clients` 查询都不产生进程。

## 为什么这么做

| 维度 | 每请求一个 Shell（已选） | 进程内单例 Shell（ADR-003 原状） |
| --- | --- | --- |
| 请求隔离 | 目录/环境变量/shell 变量完全隔离 | 跨请求泄漏，串行化无法解决 |
| 生命周期 | 请求结束即销毁 | 与请求无关，可能整个进程生命周期存活 |
| 超时影响半径 | 只销毁触发超时的那个请求 | 销毁全局共享 Shell，波及所有请求 |
| 资源边界 | `max-concurrent-shells` + LRU + 空闲回收 | 无上限、无回收 |
| 请求内连续性 | 同一请求顺序复用同一 Shell，保留 `cd` | 保留（唯一优点） |
| 单写者保证 | 不变（仍由单线程执行器串行化） | 不变 |
| 额外成本 | 每个请求首次本地命令多一次进程启动 | 无 |

选它的关键理由是：**改动不推翻 ADR-003 的任何并发决策**。单线程执行器让注册表天然简单——创建只发生在一个线程、同一时刻只有一个会话在使用中，因此不需要为每个 Shell 引入执行器、锁或容量治理；`Future.get` 超时与兜底执行拒绝语义也原样保留。

## 被否决的替代方案

| 方案 | 否决原因 |
| --- | --- |
| 每次 `execute()` 调用一个 Shell（命令级作用域） | 需求原文为"每次请求"；命令级作用域会失去同一请求内多条命令的 `cd`/环境变量连续性，并为每条命令支付进程启动成本。 |
| 保留单例 Shell，仅在请求结束时"清空状态"（如 `cd` 回工作目录） | 无法清理环境变量、shell 变量、后台子进程与临时文件，不是隔离；且仍是一个长期存活进程。 |
| 每个请求一个独立执行器/线程 | 等于把 ADR-003 的容量治理乘以请求数；本任务不要求并发执行本地命令。 |
| 用 `sessionId` 或 `agentId:userId` 作为 key | 同一会话的多次请求会复用同一 key，变成跨请求共享，与目标相反；复合键还存在分隔符碰撞风险。 |
| 定时任务做空闲回收 | 引入新线程与生命周期管理；"新建 Shell 前机会式清扫"已足够，因为 Shell 只会因新命令而产生。 |
| 在 `AgentStreamBridge.clear` 内部触发 Shell 销毁 | 流式传输组件不应知道命令执行器的存在；销毁应由 Controller 的生命周期清理统一编排。 |
| 用 MySQL/Redis 记录 Shell 归属 | Shell 是进程级资源，跨实例共享无意义；重启即进程消失，持久化只增加不一致风险。 |
| 操作系统级隔离（cgroup/job object/容器） | 属于部署与沙箱议题，且不能解决"跨请求共享同一 Shell"的隔离问题。 |

## 后果

- **每个请求首次执行本地命令时多一次进程启动**（Windows 上 `pwsh` 通常数百毫秒）；同一请求内后续命令复用，不再重复付出该成本。
- **请求结束、超时、淘汰、空闲回收、应用关闭都会重置该 Shell 的 `cd`/环境变量。** 这是刻意的行为，已写入 `command-gateway/SKILL.md`，Agent 不应依赖跨请求状态。
- **`max-concurrent-shells` 上限会淘汰空闲 Shell。** 被淘汰请求的下一条命令会重建 Shell；淘汰是"优先保新请求可用性"的取舍。
- **请求结束与命令执行存在理论竞态。** emitter 20 分钟超时或客户端断开可能发生在命令执行中途，表现为该命令以 `failed`（"本地 Shell 意外终止"）返回。销毁实现刻意不等待命令结束，以避免阻塞 Controller 线程。
- **单线程执行器仍是吞吐瓶颈。** 作用域隔离后，不同请求的 Shell 仍共享同一个执行线程；若确需并发执行本地命令，应另立任务评估"每请求执行器"。
- **`destroyForcibly()` 不保证立即生效。** 极端情况下可能残留僵尸进程与登记项；空闲清扫会持续重试，`max-concurrent-shells` 提供硬约束，但不得宣称绝对可靠。
- **`LocalShellScope.resolve()` 成为唯一 key 决策点。** 若未来需要改为命令级作用域或引入配置切换，只应修改该处。
- **测试不再需要真实 Shell。** `LocalShellLauncher` 抽象让复用/隔离/销毁/淘汰/回收都可以用内存替身断言，真实 Shell 只保留少量端到端用例。
- **ADR-002 的 Holder 边界被扩大。** `CommandExecutionContextHolder` 从"仅服务于命令审批"扩展为"命令审批 + 本地 Shell 作用域"，其"同线程执行链已验证"的前提仍然适用（两处读取都在同一执行线程内），但 ADR-002 的表述需同步修订。
- **若未来恢复 `ALLOW` 分支并放宽允许规则允许 `cd` 等有状态命令**，"同一请求内连续命令共享目录、请求结束重置"应作为业务事实写入 `business.md`。
