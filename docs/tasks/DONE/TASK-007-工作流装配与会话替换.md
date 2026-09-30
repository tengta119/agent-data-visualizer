# TASK-007 工作流装配与会话替换

## 目标

基于 TASK-006 迁入的 PaiCLI 内核，在 data-visualizer 领域层实现配置驱动的 Agent 装配、工作流和会话。此任务先让新执行链可由测试和内部服务调用；HTTP 生产流量在 TASK-008 切换。

上位方案：[PaiCLI 源码迁移总方案](PAICLI-MIGRATION-PLAN.md)。前置任务：[TASK-006](TASK-006-PaiCLI内核源码迁入.md)。

## 前置条件

- TASK-006 的迁入模块、嵌入 API、资源和 POM 接线已落地。
- 阅读现有 `AiAgentConfigTableVO`、`AiAgentAutoConfig`、`ArmoryService`、工作流节点和默认 `agent/data-visualizer-agent.yml`。
- 保持默认 Agent ID `100003`、入口 `sequential_draw_process` 与三个 output key 的含义。

## 实现范围

1. 增加配置解析/校验和 PaiCLI 模型工厂。继续接受现有 YAML 的 `base-url`、`api-key`、`completions-path`、`model`、Agent 指令、工作流和 Runner 字段。API key 仅作为运行期配置，不写日志、事件或异常正文。对缺字段、重复名字、未知子 Agent/入口、循环次数非法及不支持的工具/MCP 配置在切换前报错。
2. 新增 `PaiCliWorkflowEngine`：按配置执行 `sequential`、`parallel`、`loop`。默认串行流程固定按 `agent_analyst → agent_drawer → agent_reviewer`，把阶段输出写到 `analysis_result → draft_diagram → final_result`，下一阶段的 `{output_key}` 从可信工作流状态替换，不把指令降级为用户消息。并行分支隔离可变状态、按配置顺序合并；循环遵守 `max-iterations`。PaiCLI `/team` 的角色编排语义不同，不直接替代此 YAML 工作流。
3. 建立会话注册表，显式绑定 `agentId`、`userId`、`sessionId` 和配置版本。同一 session 的 turn 串行化，阶段内 PaiCLI `Agent` 历史只在所属 session 内复用。调用方传入不匹配或过期 sessionId 时给出稳定错误，不能串到其他用户/Agent。配置更新后已在途请求使用其开始时的配置快照；后续请求必须使用新配置并创建新会话，旧 sessionId 显式失效。
4. 将运行期配置更新改为“完整验证和构建 → 原子切换可查询/可调用注册表”。列表与管理查询读取同一快照；从新配置移除的 Agent 不再可新建会话。此处有意修复旧 Bean 残留问题。`enabled` 先保留现有字段语义，不在本次迁移中增加启停行为。
5. 补充假模型测试：默认三个阶段的顺序/插值、返回 `type=user` 的补充问题、并行隔离、循环上限、相同会话连续对话、错误 sessionId 拒绝、热更新成功/失败的原子性。

## 交付物

- 领域层 PaiCLI 配置工厂、工作流执行器、会话注册表和配置快照。
- 供 TASK-008 调用的同步执行和事件回调接口；接口中不出现 ADK `Runner`、`Session`、`Event`。
- 工作流与会话的聚焦测试代码。

## 验收条件

- 在假模型下，默认配置一次请求产生分析、绘图、审查三个有序结果，最终输出仍由 `final_result` 决定。
- 错误配置不会留下部分新 Agent；移除的 Agent 在配置切换后不能新建会话。
- 同一 session 不并发改写历史；不同 agentId/userId/sessionId 不互相读取历史。
- 不访问真实模型即可验证三类工作流和配置更新行为；测试代码已补齐，构建/测试由开发人员手动执行。

## 非目标与交接

本任务不改 HTTP DTO 或前端，不接通真实 Shell 审批，不删除 ADK/Spring AI 依赖。向 TASK-008 交付“创建会话、执行同步 turn、执行带事件回调 turn、取消执行”的稳定领域接口。
