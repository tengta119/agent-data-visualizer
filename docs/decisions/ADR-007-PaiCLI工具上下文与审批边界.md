# ADR-007：PaiCLI 工具上下文与审批边界

## 状态

已采用（TASK-008）。

## 背景

原 Shell 审批通过 ADK 工具执行线程上的 `ThreadLocal` 取得 requestId。PaiCLI 同轮工具可在独立线程池并行运行，不能继承或猜测 Controller 线程中的请求身份。PaiCLI 原有工具注册表也包含文件、Shell、Web 等内置能力，不能直接暴露给数据绘图 Agent。

## 决策

1. 嵌入 Agent 使用空工具注册表，只安装当前 YAML 显式声明的工具。默认 local 配置映射到 `mcp__ShellExecutor__execute`，实际命令仍调用原 `ShellExecutor`。stdio MCP 按配置建立连接并注册该服务列出的工具；旧式 SSE 传输无法由迁入的 Streamable HTTP 客户端兼容，配置时明确拒绝。
2. `CommandExecutionContext` 是不可变请求身份，随工作流阶段和 PaiCLI 并行工具任务显式传递。仅 Shell 工具适配器在调用 `ShellExecutor` 前设置 `CommandExecutionContextHolder`，并在同一线程的 `finally` 中清理。同步对话不提供 requestId，因此 Prompt 安全失败。
3. `ShellExecutor` 保持 Allow/Prompt/Forbidden 三态边界。Forbidden 直接拒绝；Prompt 等待一次性审批，批准后核对摘要和请求，再次策略审查。工具的成功/失败使用 PaiCLI `ToolOutput`，嵌入工作流遇到失败工具时走类型化错误路径。
4. 流式请求的完成、超时、异常和断开统一幂等清理：取消待审批项、工作流请求、任务 Future 与请求专属 shell，最后清除 Bridge。取消只尽力中止在途调用，不能撤销已送出的外部副作用。

## 影响

HTTP 路径与请求/响应 DTO、流式 JSON 类型和 Draw.io 转换保持兼容。旧 ADK/Spring AI 实现已在 TASK-009 删除（参见 ADR-008）。审批状态仍仅保存在当前 JVM，后端认证和跨实例恢复不在本任务范围。
