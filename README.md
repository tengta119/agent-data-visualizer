# Data Visualizer

基于 Spring Boot 和 Next.js 的 AI 绘图应用。后端按 YAML 装配“需求分析 → 绘图 → 审查”工作流，在同一进程内运行本仓库迁入的 PaiCLI 源码，通过 OpenAI 兼容模型接口生成文本或 Draw.io 图。

## 模块

- `data-visualizer-paicli`：PaiCLI ReAct 内核及受限嵌入 API；源提交和迁入文件见 [UPSTREAM-SOURCES.txt](data-visualizer-paicli/UPSTREAM-SOURCES.txt)。
- `data-visualizer-domain`：工作流、会话、工具、审批、流式事件及 Draw.io 转换。
- `data-visualizer-trigger` / `data-visualizer-api`：HTTP 接口及 DTO。
- `data-visualizer-infrastructure`：可选 Netty 远程命令网关。
- `data-visualizer-app`：Spring Boot 启动模块和默认 Agent YAML。
- `data-visualizer-front`：Next.js 前端。

应用由本仓库 Maven reactor 直接构建；无需安装或启动 PaiCLI 原仓库。旧 Google ADK、Spring AI 和 RxJava Agent 执行路径已移除。

## 本地运行

需要 Java 17、Maven 和可访问的 OpenAI 兼容模型端点。以下命令在 PowerShell 中执行；仓库 `AGENTS.md` 要求构建和测试由开发人员手动执行。

```powershell
mvn.cmd -B -ntp -DskipTests package
$env:OPEN_AI_KEY = '<your-model-api-key>'
java -jar data-visualizer-app/target/ai-agent-scaffold-lite-app.jar
```

默认 dev profile 使用 HTTP `8091`、Netty `9077`，导入 `data-visualizer-app/src/main/resources/agent/data-visualizer-agent.yml`。启动时优先读取工作目录 `config/data-visualizer-agent.yml`；配置装配失败会记录错误，但 HTTP 服务仍可能启动。密钥不要提交到仓库。远程命令客户端、MySQL 和 Redis 不是默认绘图链路的前置服务。

前端开发与运行说明见 [data-visualizer-front/README.md](data-visualizer-front/README.md)。应用部署脚本和容器配置位于 `docs/dev-ops/`。

## 验收与设计

- [手动验收清单与 API 样例](docs/dev-ops/README.md)
- [当前架构](docs/architecture.md) 与 [业务行为](docs/business.md)
- [进程内迁移决定](docs/decisions/ADR-008-完成PaiCLI进程内迁移并移除旧Agent框架.md)

同步接口保留 `GET /api/v1/query_ai_agent_config_list`、`POST /api/v1/create_session`、`POST /api/v1/chat`；流式接口为 `POST /api/v1/chat_stream`，以连续 JSON 对象发送 `log/result/error/done` 与审批事件。管理配置接口位于 `/api/v1/admin/*`。当前后端未实现认证、授权或会话持久化，部署时应限制这些接口与命令网关的可访问范围。
