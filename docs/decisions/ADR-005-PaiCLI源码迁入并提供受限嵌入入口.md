# ADR-005 PaiCLI 源码迁入并提供受限嵌入入口

状态：已采纳；TASK-009 已完成业务路径切换与旧框架清理（参见 ADR-008）

## 背景

data-visualizer 计划把 Spring AI + Google ADK 的 Agent 底层替换为 PaiCLI。用户要求把 PaiCLI 代码迁入本仓库，而不是依赖外部 PaiCLI JAR、进程或 HTTP API。当前 HTTP 请求仍走原有工作流，需要按任务顺序渐进迁移。

## 决定

新增 Maven 模块 `data-visualizer-paicli`，从固定上游提交迁入 Agent/ReAct 所需源码与资源，保留 `com.paicli` 包名并记录逐文件清单。该模块提供进程内 `EmbeddedAgent` 入口：宿主显式传入可信 system instruction 和允许的工具；默认工具列表为空；运行事件、最终结果、模型故障和取消通过类型化 API 返回。嵌入入口使用内存记忆，不启用工具结果落盘，也不启动终端或网络服务。

模块先加入 reactor，不作为现有业务模块的依赖。后续任务接入工作流、接口和审批后，再移除旧框架依赖。

## 理由与影响

源码迁入使应用构建和运行不依赖另一仓库的构建产物或独立进程。受限入口让宿主决定各阶段的提示词和工具权限，也使 HTTP 层能按错误类型处理失败与取消。代价是迁入源码需维护本地差异；`UPSTREAM-SOURCES.txt` 和模块 README 记录基线及改动点，供后续同步与审查。

目前的业务行为与对外接口没有变化，因此 `docs/business.md` 无需修改。构建、测试及端到端验证由开发人员按仓库规则手动执行。
