# ADR-008 完成 PaiCLI 进程内迁移并移除旧 Agent 框架

状态：已采纳（TASK-009）

## 背景

TASK-006 至 TASK-008 已将 PaiCLI ReAct 源码、工作流和同步/流式 HTTP 接线迁入本仓库。原 ADK Runner、Spring AI ChatModel/Tool Callback、MCP 工厂及其装配树仍保留在源码和 Maven 依赖中，会形成两套可误用的执行路径。

## 决定

删除仅供旧执行路径使用的源码、示例测试和 Maven 依赖。Spring Boot 继续负责 HTTP、配置与生命周期；`data-visualizer-paicli` 模块由同一 Maven reactor 构建，负责模型调用和 ReAct；`PaiCliWorkflowRuntime` 负责配置快照、工作流与会话；宿主仅按 YAML 装配受限 Shell/stdio MCP 工具。原 Shell 策略、审批、Netty 网关和 Draw.io 转换器继续使用。

默认 YAML 的本地工具名改为 `ShellExecutor`；旧 `ShellExecutorToolCallbackProvider` 名称只作为外部 YAML 兼容别名，不会创建 Spring AI Bean。旧 ADK `plugin-name-list` 无等价运行语义，非空时拒绝装配并提示移除，避免静默忽略。SSE MCP 仍被显式拒绝。

PaiCLI 上游源码基线为提交 `36a26776b60b1a7e02a91209a81e8c4412a863cb`，逐文件清单和本地差异见 `data-visualizer-paicli/UPSTREAM-SOURCES.txt` 与模块 README。应用构建和运行不需要安装、启动或引用 PaiCLI 原仓库。

## 边界与取舍

- 同一会话绑定 agentId、userId 和配置版本，turn 串行执行；配置更新后旧会话失效，状态仅在当前 JVM。
- 工具注册表默认不暴露 PaiCLI 内置 Shell、文件、Web 工具；Shell 执行仍经过 Allow/Prompt/Forbidden 和一次性审批。该策略不是操作系统沙箱或后端身份授权。
- HTTP 路径、DTO、连续 JSON 流式消息及 Draw.io 输出合同继续保留。真实模型、远程命令和部署环境仍需按手动验收清单分别验证。
- 代价是迁入源码需要维护本地差异；升级上游时按记录的源提交逐文件审查，避免整仓覆盖。
