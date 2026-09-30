# PaiCLI 源码迁移总方案

本方案已拆为四个顺序执行的任务：[TASK-006](TASK-006-PaiCLI内核源码迁入.md) → [TASK-007](TASK-007-工作流装配与会话替换.md) → [TASK-008](TASK-008-对话接口工具与审批接线.md) → [TASK-009](TASK-009-移除旧框架与迁移验收.md)。每份任务文档均可独立交给后续实现者。任务文件位于本目录；当前 `.gitignore` 不再忽略 `docs/tasks/`。

## 目标与边界

把 `D:\project\erge2\paicli\paicli` 中承担 Agent 执行的源码迁入本仓库，由本项目自行构建和维护，替换 Spring AI + Google ADK。保留 Next.js 前端、现有 `/api/v1/*` HTTP 路径、请求/响应 DTO、流式消息类型，以及默认“需求分析 → 绘图 → 审查”流程和 Draw.io 交付格式。Spring Boot 继续承担 HTTP、配置和生命周期管理；PaiCLI 代码承担模型调用、ReAct、工具策略和 Agent 上下文。

此次按**进程内源码迁移**设计，不依赖 PaiCLI 原仓库的本地 Maven 安装、fat jar、CLI 进程或 Runtime HTTP API。PaiCLI Runtime API 每个 turn 新建 Agent，不能直接满足本项目的连续会话和三阶段绘图要求。

方案依据：PaiCLI 源码当前提交 `36a26776b60b1a7e02a91209a81e8c4412a863cb`，以及两仓库的 `docs/architecture.md`、`docs/business.md`。迁移开始时应再次记录源提交和所选文件清单；PaiCLI 仓库中的未跟踪文件不自动纳入。

## 迁移对应关系

| 现有实现 | 目标实现 | 对外兼容要求 |
| --- | --- | --- |
| ADK `InMemoryRunner`、`Session`、`Event` | 本项目 `PaiCliWorkflowEngine`、会话注册表、类型化执行事件 | `create_session` 返回稳定 sessionId；同步接口仍返回最终文本或图形 |
| ADK `LlmAgent` 与 sequential/parallel/loop 节点 | 按 YAML 解析的工作流执行器，阶段内使用迁入的 PaiCLI `Agent` | 默认三个阶段及 `analysis_result → draft_diagram → final_result` 不变；配置中其他工作流类型也须有明确实现 |
| Spring AI `OpenAiChatModel` | PaiCLI `LlmClient` 与可配置 OpenAI 兼容客户端 | 继续读取 YAML 中的模型名、base URL、API key 和 completions path；密钥不得写进日志 |
| Spring AI Tool Callback、ADK Plugin | 受限 `ToolRegistry`、请求事件监听器、`AgentStreamBridge` 适配器 | 仅暴露 YAML 声明的工具；保留 `log/result/error/done`、requestId 和审批事件 |
| ADK Skills/MCP 装配 | PaiCLI Skill/MCP 适配，并保留资源型绘图技能 | `agent/skills` 的绘图规范仍进入对应阶段上下文 |
| 现有 `ShellExecutor` | PaiCLI 工具调用适配到现有 `ShellExecutor` | 保留 Allow/Prompt/Forbidden、一次性审批、请求专属 shell、超时和清理语义 |
| `JsonToDrawioConverter` 与 Controller 结果解析 | 保留现有确定性转换器和结果解析 | `drawio_graph`、旧 XML/时序图协议及文本兜底不变 |

## 实施顺序

### 方案一：迁入可嵌入的 PaiCLI 内核

1. 新增 `data-visualizer-paicli` Maven 模块，保留迁入源码的 `com.paicli` 包名；本项目新写的适配层使用 `top.lbwxxc.ai`。按 Java import、资源加载和反射引用做依赖闭包，只迁入运行所需的 Agent、LLM、工具/策略、提示词、上下文、Skill/MCP 等代码及其必要依赖。CLI/TUI、微信、Runtime API、后台任务、评测和演示入口不作为迁移目标；若核心代码对其有编译依赖，先拆开依赖，不把无关入口整体搬入。
2. 复制内核运行需要的 `prompts/` 与内置 Skill 资源，避免把 PaiCLI 的 `logback.xml` 覆盖 Spring Boot 日志配置，也不复制评测脚本、用户配置、密钥或 `target/`。在模块内记录源仓库提交、文件清单和本项目修改点。
3. 为迁入的 PaiCLI `Agent` 增加明确的嵌入 API：可信的阶段 system instruction、受限工具集合、类型化最终结果/失败、事件监听器、取消入口。保持原有 ReAct 与工具策略逻辑，避免把阶段指令拼进普通用户消息，也避免解析“❌ 调用失败”之类的展示文本来判断成功。
4. 在根 POM 接入新模块并显式管理其真实运行依赖。检查 Jackson、OkHttp、SLF4J/Logback 与 Spring Boot 的版本及装配边界；不引入 PaiCLI 原仓库的 Maven 坐标或 fat jar。

**阶段验收**：新模块源码和资源在本仓库自足；可通过内存假模型构造嵌入 Agent；默认不创建终端 UI、不启动本机 HTTP 服务、不暴露 PaiCLI 全量文件/命令工具。

### 方案二：替换 Agent 装配、工作流与会话

1. 新建配置校验与模型工厂，映射现有 `AiAgentConfigTableVO`。支持 YAML 当前使用的 OpenAI 兼容端点；缺少关键字段、未知模型能力、重复 Agent/工作流名称及无效子 Agent 引用应在装配前报错。
2. 以配置驱动的 `PaiCliWorkflowEngine` 替换 ADK 节点链。串行工作流按配置顺序执行并写入 output key；并行工作流隔离每个分支状态后按配置顺序合并；循环工作流遵守 `max-iterations`。默认 Runner 仍指向 `sequential_draw_process`。不得把 PaiCLI `/team` 直接当成 ADK 的串行工作流。
3. 建立 `agentId + userId + sessionId` 明确关联的会话注册表。每个阶段的 PaiCLI Agent 会话历史按 session 保存，同一 session 的并发 turn 串行化，避免 PaiCLI 的可变 `conversationHistory` 被两个请求同时修改。校验调用方传入的 sessionId 与 agentId/userId 关联；定义配置替换时旧会话的失效或版本绑定规则。
4. 运行期 Agent 配置先完整校验并构建新快照，再一次性切换可查询、可调用的注册表；查询列表与当前配置使用同一快照。移除配置中的 Agent 不继续作为旧 Bean 残留，这属于对当前“移除后仍可调用”缺陷的有意修正。`enabled` 暂按现有字段语义保留，不在本次迁移中悄悄赋予启停效果；如需启停能力，另立明确的业务变更。

**阶段验收**：不访问真实模型即可验证默认三阶段顺序、output key 插值、文本补充问题、循环上限、并行分支隔离、会话连续性，以及错误配置不会留下部分生效状态。

### 方案三：接通同步/流式 API、工具与审批

1. `IChatService` 改为本项目定义的执行结果和事件回调，不再向 Controller 暴露 ADK `Flowable<Event>`。同步 `/chat` 等待最终结果；流式 `/chat_stream` 将 PaiCLI 的阶段、模型输出和工具事件映射到现有连续 JSON，完成时只发一次 `result` 和 `done`。保留 `AgentServiceController` 的 Draw.io JSON DSL 转 XML 和旧 XML 解析路径。
2. 让受限工具注册表只暴露当前 YAML 声明的工具。`ShellExecutorToolCallbackProvider` 改为 PaiCLI 工具适配器，实际命令仍走原 `ShellExecutor` 与现有审查/审批服务；`clients` 和 remote 路径保持现有行为。stdio/SSE MCP 与目录/资源 Skills 逐一映射，不支持的配置应明确拒绝装配，不能悄悄忽略。
3. 请求上下文不能仅靠现有 Controller 线程的 `ThreadLocal` 假设：PaiCLI 可能并行执行工具。将不可变 requestId/agentId/userId/sessionId 显式传入工具执行上下文，在线程入口设置并在 `finally` 清理，审批与日志均绑定同一 requestId。流结束、超时、断开和取消继续清理 Emitter、待审批和本地 shell；向 PaiCLI 传播取消并尽力停止在途模型调用。
4. 错误经类型化结果转为原有同步错误响应或流式 `error`。模型调用失败、工具拒绝、审批拒绝、流断连不得被误报为成功的绘图结果。

**阶段验收**：现有 HTTP 路径和 DTO 可继续由前端使用；流式日志、结果、完成、审批请求和审批决定的顺序及 requestId 一致；并发请求的日志、审批和 shell 状态互不串扰；Draw.io 正常和兜底路径均可用。

### 方案四：移除旧框架并收尾

1. 删除不再使用的 ADK/Spring AI 装配节点、插件、MCP Callback、补丁类和旧测试；保留仍被 PaiCLI 适配器使用的 `ShellExecutor`、审批/策略、Netty、Draw.io 转换器。清除生产代码和 POM 中的 Google ADK、Spring AI、RxJava 及仅服务旧节点链的依赖。测试中的旧框架示例应删除或迁成 PaiCLI 测试，避免隐含旧依赖。
2. 补充聚焦测试：配置装配与热更新、三种工作流、会话归属/并发、同步与流式 Controller 合同、工具白名单、审批二次审查和取消、Draw.io 解析。使用假 LLM 和假工具做确定性验证，不在自动测试中调用付费模型或执行真实远程命令。
3. 按仓库规则检查并更新 `docs/architecture.md`、`docs/business.md`，新增 ADR 记录“源码迁入并进程内嵌入 PaiCLI”以及工具/会话边界。同步更新部署说明和 YAML 字段说明；只有代码落地后才把新架构写成已实现事实。
4. 开发人员手动执行 Maven 构建/测试与必要的端到端联调。本仓库 `AGENTS.md` 明确要求由开发人员手动执行构建、测试命令；实施过程中只做静态检查并提交测试代码。若改动前端，先读前端 `AGENTS.md`，再按其要求手动验证并运行 ESLint。

**最终验收**：生产源码/POM 不再引用 Spring AI、Google ADK；应用从本仓库的 PaiCLI 模块运行默认绘图流程；原有同步、流式、审批和 Draw.io 对外合同通过开发人员验证；不需要启动或安装 PaiCLI 原仓库。

## 主要风险与控制

- **源码耦合与体积**：PaiCLI `Agent` 依赖工具、记忆、提示词等多个包。先做依赖闭包再迁移，并把源提交和改动清单固定在仓库，后续升级以文件差异审查，不整仓覆盖。
- **默认工具权限过宽**：PaiCLI `ToolRegistry` 默认注册文件、Shell、Web 等工具。嵌入模式采用空或受限注册表，只按 data-visualizer 配置显式启用；保留现有 Shell 命令审查作为最终执行边界。
- **提示词和输出格式漂移**：绘图阶段的 YAML instruction 作为可信 system instruction；为 JSON DSL、时序图旧 XML 和“请补充信息”分别设固定样例与结果解析测试。
- **线程与取消差异**：PaiCLI 的流式回调和并行工具执行与 ADK Event 流线程模型不同。审批上下文、会话锁、取消和资源清理必须在第三阶段验证，不沿用“同线程传递”的旧假设。
- **配置切换与历史会话**：新快照生效、旧请求结束、旧 session 的处理规则必须显式定义，避免热更新后旧 Agent/模型混用。

## 执行约定

四个方案按顺序实施。每阶段先保留可审查的代码与测试，再进入下一阶段；在方案四完成前不能宣布框架已替换。当前文件是**实施计划**，不代表以上能力已经实现。
