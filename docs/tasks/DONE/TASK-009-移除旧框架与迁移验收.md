# TASK-009 移除旧框架与迁移验收

## 目标

在 TASK-008 已让生产对话路径运行 PaiCLI 后，清理 Spring AI + Google ADK 的源码与依赖，完成测试代码、文档和部署说明，使迁移结果可审查、可由开发人员手动验证。

上位方案：[PaiCLI 源码迁移总方案](PAICLI-MIGRATION-PLAN.md)。前置任务：[TASK-008](TASK-008-对话接口工具与审批接线.md)。

## 前置条件

- 同步和流式生产入口已经调用 PaiCLI 工作流；默认分析、绘图、审查可通过假模型验证。
- TASK-008 提供待删旧代码清单，以及 Shell、审批、Netty、Draw.io 等保留代码的引用检查。

## 实现范围

1. 删除仅服务旧框架的 ADK Runner/Plugin、Spring AI ChatModel/Tool Callback/MCP 工厂、装配节点链、补丁类与失效的旧示例测试。保留并必要时重定位仍在 PaiCLI 路径使用的 `ShellExecutor`、命令策略/审批、Netty、`JsonToDrawioConverter`。检查生产代码、测试、资源 YAML 和 POM，清除不再需要的 Google ADK、Spring AI、RxJava、旧装配框架及其传递依赖。
2. 补齐/整理聚焦测试代码：配置校验与原子热更新、三种工作流、会话隔离和配置版本、同步/流式对外合同、工具白名单、审批二次审查和请求清理、Draw.io JSON DSL 与旧 XML 兜底。使用假 LLM 和假工具，默认不调用付费模型、不执行远程命令。
3. 只在实现完成后更新 `docs/architecture.md`、`docs/business.md` 的当前事实；新增 ADR 解释为何进程内迁入 PaiCLI 源码、如何限定工具与会话边界。更新运行/部署说明，确认不再要求安装或启动 PaiCLI 原仓库。
4. 整理开发人员手动验证清单与请求/响应样例：Maven 构建/测试、启动后的同步文本和 Draw.io 图、流式 `log/result/done`、Prompt 批准/拒绝与断连清理、配置热更新。若实际修改前端，先读前端 `AGENTS.md` 并执行 ESLint/手动验证；无前端改动则不增加前端测试框架。

## 交付物

- 无旧框架生产引用的源码和 POM；仍可运行的 PaiCLI 绘图链路。
- 聚焦测试代码、架构/业务文档、ADR 和开发人员手动验证清单。
- 可供 PR 说明使用的影响模块、验证命令及 API 请求/响应样例。

## 最终验收条件

- `data-visualizer-*/src/main` 和生产 POM 不再引用 Spring AI、Google ADK 或 ADK/RxJava 执行类型；PaiCLI 迁入模块由本仓库直接构建。
- 对外 API、流式消息、默认三阶段流程、Draw.io 图形和命令审批按 TASK-008 合同保留。
- `docs/architecture.md`、`docs/business.md` 只描述已落地行为，ADR 记录决定与取舍；源码溯源清单指向实际迁入的 PaiCLI 提交。
- 测试代码与手动验证清单齐备。依仓库 `AGENTS.md`，构建、测试由开发人员手动执行；若未执行，交付时明确标为未验证，不宣称通过。

## 非目标

不新增用户认证、图表持久化、支付或新的前端功能；这些是独立业务变更。迁移完成不能把 PaiCLI 的路径围栏、命令黑名单或默认 HITL 宣称为完整沙箱/后端授权。
