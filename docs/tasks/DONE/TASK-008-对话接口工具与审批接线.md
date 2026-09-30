# TASK-008 对话接口、工具与审批接线

## 目标

把 data-visualizer 的同步/流式 HTTP 调用切到 TASK-007 的 PaiCLI 工作流，并将 YAML 工具、Skills、MCP 与原有命令策略/审批接入受限 PaiCLI 工具注册表。完成后生产对话路径已使用迁入源码；旧框架源码和依赖留给 TASK-009 清理。

上位方案：[PaiCLI 源码迁移总方案](PAICLI-MIGRATION-PLAN.md)。前置任务：[TASK-007](TASK-007-工作流装配与会话替换.md)。

## 对外合同

- 保留 `/api/v1/query_ai_agent_config_list`、`create_session`、`chat`、`chat_stream`、`chat_stream/{requestId}/approval` 和 `/api/v1/admin/*` 路径、方法与 DTO。
- 同步最终响应继续为 `type=user|drawio` 加 `content`。`drawio_graph` 仍由 `JsonToDrawioConverter` 转为 XML；时序图旧 XML、`drawio_done` 和纯 XML 仍可解析。
- 流式响应继续是连续 JSON 对象，保留 `log/result/error/done`、`approval_required/approval_resolved`、`sessionId`、`requestId` 和最终 `stage=user|drawio`。`result` 与 `done` 各只发送一次。

## 实现范围

1. `IChatService` 与 Controller 改用本项目定义的类型化结果和事件回调，去除接口上的 ADK `Flowable<Event>`。同步路径等待工作流终结；流式路径把 PaiCLI 阶段、模型正文、工具事件路由到 `AgentStreamBridge`。保留 Controller 的结果解析器和 Draw.io 转换器，不将中间阶段误作最终结果。
2. 为受限 `ToolRegistry` 增加仅按当前 YAML 启用工具的装配。默认 `ShellExecutorToolCallbackProvider` 改为 PaiCLI 工具适配器，调用原 `ShellExecutor.execute`，保持 `clients`、local、remote、Allow/Prompt/Forbidden、审批后二次审查及返回结构。目录/资源 Skills 与 stdio/SSE MCP 逐项映射；不能支持的配置在装配时明确报错，不能默默丢弃。
3. 请求上下文以不可变值显式传过模型/工具回调和可能的并行工具线程。只有在调用原 `ShellExecutor` 的线程入口设置 `CommandExecutionContextHolder`，并在 `finally` 清理。审批、日志和 shell 作用域统一使用同一 requestId；同步 `/chat` 没有流式审批通道，Prompt 命令继续安全失败。
4. 流完成、客户端断开、超时和异常时，幂等清理 Emitter、待审批、请求专属 shell 与 PaiCLI 执行句柄。把取消传播到在途模型调用和工具执行；不能保证停止的底层副作用要在错误/日志语义中准确表达。保持审批并发上限与单次决定的原有规则。
5. 模型、工具、审批和解析失败使用类型化错误路径，映射到现有同步错误响应或流式 `error`，不能把 PaiCLI 的展示文本错误当作成功结果。补充 Controller、流式顺序、工具白名单、审批 requestId 关联、并发隔离和取消的聚焦测试代码。

## 交付物

- 切至 PaiCLI 工作流的同步/流式 Controller 和领域接口。
- YAML → 受限 PaiCLI 工具/Skill/MCP 的适配实现。
- 保留原命令审批、Netty 网关和 Draw.io 转换器的兼容接线。

## 验收条件

- 现有前端无需改请求结构即可收到文本/图形；同一张 `drawio_graph` 在同步、流式路径产生同样的 XML。
- 日志、结果、完成和审批事件按 requestId 隔离；并发请求不串日志、审批或 shell 状态。
- 未在 YAML 授权的 PaiCLI 内置工具不出现在模型工具列表，也不能通过名称直接执行。
- Prompt 的批准、拒绝、过期、断连与二次审查均维持原执行边界；资源清理幂等。
- 测试代码已补齐；真实模型/远程命令联调和构建/测试由开发人员手动执行。

## 非目标与交接

本任务不扩展后端认证、不新增持久化或前端功能，也不删除旧框架文件。向 TASK-009 交付生产路径的完整 PaiCLI 接线、兼容测试及待删旧代码清单。
