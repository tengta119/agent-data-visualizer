# ADR-003 本地命令执行采用有界单线程执行器 + Future 超时，替代 synchronized 串行块

- 状态：已接受
- 日期：2026-09
- 关联任务：`docs/tasks/DONE/TASK-002-命令执行自动审查.md`、`docs/tasks/DONE/TASK-003-命令执行交互审批.md`（两份任务的风险章节要求限制"执行超时"与"并发写入同一个长期 Shell 的风险"）
- 关联代码：`data-visualizer-domain/.../shell/ShellExecutor.java`、`shell/approval/CommandApprovalService.java`、`shell/approval/PendingApprovalStore.java`、`shell/policy/CommandExecutionPolicyProperties.java`
- 后续修订：本地 Shell 实例的作用域（本 ADR 中被否决方案里的"每次请求启动独立短生命周期 Shell"）已由 `ADR-004-本地Shell作用域改为每请求一个独立短生命周期进程.md` 采纳，因此本 ADR 关于"进程内单例 Shell 及其超时销毁粒度"的描述已被取代；**执行器形态、`Future.get` 超时语义、`timeout`/`unavailable` 状态码、完成标记读取与弹底执行拒绝的决策仍然有效**。

## 背景

`ShellExecutor` 是 Spring 单例工具，所有 Agent 请求共享同一条长期存活的 Shell 进程（`writer`/`reader`/`shellName` 为实例字段）。原先本地路径用 `synchronized (localExecutionLock)` 串行化"写入命令 + 读到结束标记"的整段逻辑，因此**不存在两个线程同时写同一 Shell 的数据竞争**。

但该方案有三个无法靠加锁解决的问题：

1. **挂死命令会永久占住本地 Shell。** 读循环是 `reader.readLine()`，既没有超时也不响应中断。只要一条命令永不输出结束标记（等待 stdin、`tail -f`、长时间任务），持锁线程就永久阻塞在 `readLine()`，**整个 JVM 后续所有 local 命令全部挂死**。
2. **等待线程只能无界堆积。** `synchronized` 是一把无界、不公平的队列，线程在监视器上无限等待；这些线程来自 `AgentServiceController` 的 `CompletableFuture.runAsync`（JDK common pool，并行度约 `CPU-1`），少数几条挂死命令即可耗尽全部执行线程。
3. **跨请求状态泄漏。** 长驻 Shell 会保留前一个请求的 `cd`、环境变量等状态。串行化只能防止命令交错，不能提供隔离。

同时，TASK-003 的交互审批路径存在同类问题：`CommandApprovalService.requestApproval` 会用 `CompletableFuture.get(60s)` 等待用户决定，而**没有并发上限**，多个请求同时等待审批同样会耗尽 common pool。

## 决策

**本地命令执行改为"有界单线程执行器 + `Future.get(timeout)`"，删除 `synchronized` 块；命令审批增加两级并发上限。**

1. **执行器形态**：`ThreadPoolExecutor(corePoolSize=1, maximumPoolSize=1, ArrayBlockingQueue(capacity), CallerRunsPolicy)`，线程名为 `local-shell-executor` 的 daemon 线程。容量由 `command.execution.policy.local-execution-queue-capacity` 配置（默认 64）。
2. **调用侧超时**：`executeLocal` 提交任务后 `future.get(timeout)` 等待，超时时间由 `local-execution-timeout-millis` 配置（默认 30 秒，上限 `...-max` 默认 300 秒）。
3. **超时可恢复**：超时后调用线程 `cancel(true)` 并**强制销毁当前 Shell 进程**（`shellProcessRef.destroyForcibly()`），使阻塞在 `readLine()` 的执行器线程收到 EOF 后自行清理。命令返回 `timeout`，下一条命令会惰性重建 Shell。进程引用是唯一允许跨线程访问的字段（`AtomicReference<Process>`），因为它存在的唯一目的就是解除另一个线程的阻塞。
4. **兜底执行必须拒绝**：`shellProcessRef` 之外的 Shell 状态（`writer`/`reader`/`shellName`）只在专用执行线程内读写，由 `ReentrantLock` 保证可见性（执行器线程被替换时仍需内存屏障）。`CallerRunsPolicy` 会在队列满或执行器已关闭时于调用线程上运行任务体，任务体首句即校验"当前线程是否为专用执行线程"，不是则直接抛出并返回 `unavailable`。
5. **状态语义扩展**：`CommandResponse.responseStatus` 新增 `timeout`（本地执行超时）与 `unavailable`（执行器已关闭/队列满/被中断），与既有 `success/failed/forbidden/invalid` 并存，字段与名称不变。
6. **审批并发上限（两级）**：`max-pending-approvals`（整个 JVM，默认 5）与 `max-pending-approvals-per-request`（单个 requestId，默认 1）。只统计 `PENDING` 记录；上限判断与记录写入在 `CommandApprovalService.admissionLock` 内原子完成。超限的命令**直接返回 `forbidden`**：不创建待审批记录、不发送 `approval_required`、不进入等待，因此不占用 Agent 执行线程。

## 为什么这么做

| 维度 | 有界单线程执行器 + Future 超时（已选） | `synchronized` 串行块（被否决） |
| --- | --- | --- |
| 挂死命令 | 超时后强制重启 Shell，返回 `timeout`，执行器可继续服务 | 持锁线程永久阻塞，整个 JVM 的 local 命令全部挂死 |
| 等待线程 | 有界队列 + 超时，容量可配 | 无界、不可中断、不公平 |
| 可观测性 | 可读队列长度/活动线程数，状态区分为 `timeout`/`unavailable`/`failed` | 只能看到"卡住"，无法区分原因 |
| 中断语义 | **中断仍可退出（先强制销毁进程再解阻塞）** | 中断无效，`readLine()` 不可中断 |
| 单写者保证 | 唯一执行线程 +（对兜底路径的）线程身份校验 | 监视器保证，但同时带来上述全部缺点 |
| 审批线程占用 | 两级上限，超限快速失败 | 无上限，等待审批即可耗尽 common pool |

## 被否决的替代方案

| 方案 | 否决原因 |
| --- | --- |
| 保留 `synchronized`，只给 `readLine()` 加超时 | `BufferedReader.readLine()` 无超时 API；改用非阻塞读需要重写读循环，且中断问题依然存在。 |
| 保留 `synchronized` + `tryLock(timeout)` 限制等待 | 只解决"等锁"时长，不解决持锁线程永久阻塞；问题 1 依然存在。 |
| `newSingleThreadExecutor()` + `AbortPolicy` | 无界 `LinkedBlockingQueue`，任务可无限堆积；`AbortPolicy` 抛出的 `RejectedExecutionException` 需要自行映射状态，且与仓库既有 `ThreadPoolConfig` 的 `CallerRunsPolicy` 习惯不一致。 |
| 允许 `CallerRunsPolicy` 在调用线程真正执行 Shell 交互 | 破坏"同一时刻单写者"不变量，且调用线程上的 `readLine()` 没有 `Future.get` 超时保护——等于把问题 1 搬到调用线程。 |
| 每次请求启动独立短生命周期 Shell | 能同时解决隔离与并发，但每条命令都要支付进程启动成本，且改变 `cd` 状态在单轮对话内不再保持；属于更大的架构改动，另行评估。 |
| 为本地命令设置全局并发上限（而非上限 1） | 本地 Shell 是单个进程、单对读写管道，并发度大于 1 无法保证命令与结束标记的对应关系。 |
| 审批超限时把命令转为 `failed` 或阻塞等待 | `failed` 会被 Agent 误认为"执行失败可重试"；阻塞等待正是要消除的行为（`forbidden` 语义为"未执行且不应重试"）。 |

## 后果

- 本地命令串行执行：吞吐上限为一条命令，长命令会阻塞其他请求的 local 命令。超时上限（默认 30 秒，最多 300 秒）把这种阻塞限制在可控范围——这是有意的取舍。
- 超时后命令可能已经部分执行：`timeout` 只保证"调用方不再等待且 Shell 被重建"，**不保证底层命令未产生副作用**。与 TASK-002 风险 12 的"底层命令无法可靠撤回"一致。
- `timeout` 与 `unavailable` 是新增的状态，Agent 侧需要在 Skill 指引下区分"重试"与"不要重试"。
- 审批并发上限使超限命令返回 `forbidden`；单请求默认上限 1 意味着同一请求在用户未决定前无法再发起第二条待审批命令。需要并发审批时调整 `max-pending-approvals-per-request`，但不建议超过执行器容量。
- `ShellExecutor` 新增 `@PreDestroy`：Spring 上下文关闭时 `shutdownNow()` 并销毁 Shell 进程。
- 若未来把本地 Shell 改为每请求独立进程，本 ADR 的第 1~4 条需整体重估；第 6 条（审批并发上限）与 Shell 形态无关，应保留。
