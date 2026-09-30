# TASK-006 PaiCLI 内核源码迁入

## 目标

把 PaiCLI 运行 Agent 所需的源码和资源迁入 data-visualizer，形成可由本仓库构建的 Maven 模块及受限嵌入入口。本任务不切换现有 HTTP 流量；完成后 Spring AI + Google ADK 仍服务生产路径，供 TASK-007/008 渐进替换。

上位方案：[PaiCLI 源码迁移总方案](PAICLI-MIGRATION-PLAN.md)。源仓库：`D:\project\erge2\paicli\paicli`；分析时提交：`36a26776b60b1a7e02a91209a81e8c4412a863cb`。执行时重新记录实际源提交与迁入文件清单，不复制源仓库中的未跟踪文件。

## 前置条件

- 阅读两仓库的 `AGENTS.md`、`docs/architecture.md`、`docs/business.md`，以及 PaiCLI `Agent`、`LlmClient`、`ToolRegistry`、`TurnToolPolicy`、`PromptAssembler` 的源码。
- 保留本仓库已有未提交改动；当前 `.pi/` 与此任务无关。
- 不从 PaiCLI 原仓库引用本地 jar、`systemPath` 或 Runtime HTTP API。

## 实现范围

1. 新增 `data-visualizer-paicli` 模块并加入根 Maven reactor。按 Java import、资源读取、反射引用建立运行时依赖闭包，迁入 Agent/ReAct、模型客户端、工具/策略、提示词、上下文、Skill/MCP 等必需源码。迁入源码保留 `com.paicli` 包名；项目适配层另用 `top.lbwxxc.ai`。记录迁入清单、上游提交和本地改动点。
2. 迁入必需的 `prompts/`、内置 Skill 资源。排除 CLI/TUI、微信、Runtime API、后台任务、评测、演示入口及其资源；若核心类依赖这些入口，先解耦再决定最小闭包。不要复制 `logback.xml`、`target/`、用户配置、密钥或评测脚本。
3. 为嵌入场景增加明确 API：可信的阶段 system instruction、只注册显式允许工具的注册表、类型化结果/异常、执行事件监听、取消和在途模型调用取消。PaiCLI 当前 `Agent.run` 会把模型 `IOException` 变成展示文本；嵌入 API 必须保留错误类型，不能通过解析展示文本推断成败。
4. 显式声明迁入代码的运行依赖，并检查与 Spring Boot 的 Jackson、OkHttp、SLF4J/Logback 版本和资源冲突。迁入模块不得自动打开终端 UI、HTTP 端口、微信通道或工作区写权限。
5. 加入聚焦测试代码：假 `LlmClient` 驱动正常结果、模型故障、流式事件与取消；断言嵌入模式默认无内置 Shell、文件写入、Web 等工具。

## 交付物

- `data-visualizer-paicli` 源码、资源、POM 和源文件溯源清单。
- 面向 TASK-007 的嵌入 API 与使用示例测试。
- 根 POM 模块接线；旧业务运行路径不切换。

## 验收条件

- 迁入模块只依赖本仓库源码与声明的 Maven 依赖，不需要 PaiCLI 原仓库的构建产物或进程。
- 假模型可完成一次无工具 Agent turn；失败以类型化错误返回，事件可被监听，取消可传递。
- 默认暴露工具列表为空或明确受限，不继承 PaiCLI `ToolRegistry` 的全量内置工具。
- 旧 data-visualizer HTTP/Agent 代码仍可保持原有运行路径，便于后续阶段切换。
- 测试代码已补齐；按本仓库 `AGENTS.md`，构建和测试命令由开发人员手动执行，实施者不运行。

## 非目标与交接

本任务不实现 data-visualizer YAML 工作流、会话、前端、Controller、Shell 审批或移除旧依赖。向 TASK-007 交付嵌入 API 的构造方式、事件/错误结构、工具注册方式及资源加载约定。
