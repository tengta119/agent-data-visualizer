# ADR-006 PaiCLI 工作流配置快照与会话边界

状态：已采纳（TASK-007 内部执行链；HTTP 切换留待 TASK-008）

## 背景

现有 ADK 装配过程逐个注册 Agent Bean，配置更新失败可能留下部分变更，移除配置项也不会注销旧 Bean。PaiCLI 嵌入 Agent 持有可变对话历史，不能在不同用户、会话或并发 turn 之间共用。

## 决定

领域层新增独立的 `PaiCliWorkflowRuntime`。配置先复制、完整校验并编译成不可变工作流定义，再用单次原子引用切换查询和执行快照。`enabled` 保持现有字段语义。`sequential` 按顺序传递 output key，`parallel` 为各分支复制状态并按配置顺序合并，`loop` 遵守正整数迭代上限。

sessionId 由服务生成并绑定 agentId、userId、配置版本。每个会话持有自己的阶段 Agent 历史，同一会话 turn 使用锁串行执行。已进入执行的 turn 保留其开始时快照；配置切换后旧 sessionId 的后续 turn 返回明确的过期错误。取消入口校验会话归属，并传递到活动阶段及模型调用。

## 影响

Task 007 的内部服务可通过假模型验证工作流、配置更新和会话隔离，不需要真实模型或 PaiCLI 进程。当前 HTTP 路径、管理查询、Shell 审批和旧 ADK Bean 仍按原方式运行；Task 008 需要将它们接入新服务，再处理工具与审批。新增领域服务的查询与执行接口不包含 ADK 类型。
