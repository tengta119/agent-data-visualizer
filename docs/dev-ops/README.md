# PaiCLI 迁移手动验收清单

本清单供开发人员在 PowerShell 中执行。自动测试使用假模型；真实模型、远程命令与容器部署应在各自环境单独验证。应用只需构建本仓库，不需要安装或启动 PaiCLI 原仓库。

## 1. 构建与依赖

```powershell
mvn.cmd -B -ntp -DskipTests package
mvn.cmd --% -B -ntp -Dapp.skipTests=false test
```

确认 reactor 包含 `data-visualizer-paicli`，应用 JAR 为 `data-visualizer-app/target/ai-agent-scaffold-lite-app.jar`。若测试占用默认 Netty `9077` 端口，先停止正在运行的应用再执行。检查源码与生产 POM 中没有 `org.springframework.ai`、`com.google.adk`、`io.reactivex` 和旧装配树引用；`data-visualizer-paicli/UPSTREAM-SOURCES.txt` 应指向提交 `36a26776b60b1a7e02a91209a81e8c4412a863cb`。

## 2. 启动

在仓库根目录配置有效的模型密钥和可达的 OpenAI 兼容端点。默认 Agent YAML 为 `data-visualizer-app/src/main/resources/agent/data-visualizer-agent.yml`；本地覆盖文件可放在工作目录 `config/data-visualizer-agent.yml`。覆盖文件的本地工具名使用 `ShellExecutor`，不再配置旧 ADK `plugin-name-list`。旧 `ShellExecutorToolCallbackProvider` 名称仅作为外部 YAML 的兼容别名。

```powershell
$env:OPEN_AI_KEY = '<your-model-api-key>'
java -jar data-visualizer-app/target/ai-agent-scaffold-lite-app.jar
```

默认 HTTP 端口为 `8091`，Netty 端口为 `9077`。应用进程启动不代表 Agent 装配成功；检查日志中的配置装配结果，再查询 Agent 列表。不要把密钥、包含密钥的管理接口响应或本地覆盖文件提交到 Git。

## 3. 同步 HTTP 合同

```powershell
$base = 'http://127.0.0.1:8091/api/v1'
Invoke-RestMethod "$base/query_ai_agent_config_list"
$session = Invoke-RestMethod "$base/create_session" -Method Post -ContentType 'application/json' -Body '{"agentId":"100003","userId":"manual-check"}'
$sid = $session.data.sessionId
$body = @{agentId='100003';userId='manual-check';sessionId=$sid;message='画一个开始到结束的流程图'} | ConvertTo-Json
Invoke-RestMethod "$base/chat" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

检查列表包含 `100003`，会话返回非空 `sessionId`。明确绘图请求应得到 `code=0000`、`data.type=drawio` 和完整 `<mxGraphModel>` 或 `<mxfile>`；模糊请求可得到 `data.type=user` 与补充信息。模型输出取决于实际模型；若模型没有遵守 YAML 协议，记录原始响应及日志，不将普通文本误判为图表成功。

响应形状示例（内容节选）：

```json
{"code":"0000","info":"成功","data":{"type":"drawio","content":"<mxGraphModel>...</mxGraphModel>"}}
```

## 4. 流式、工具与审批

用前端 `/chat-stream` 页面或持续读取响应的 HTTP 客户端发送同一请求到 `POST /api/v1/chat_stream`。响应是**连续 JSON 对象**，不是 SSE `data:` 行。逐条检查同一 `requestId` 的 `log`、一次 `result`、一次 `done`；图表结果的 `stage` 为 `drawio`，XML 应与相同 `drawio_graph` 的同步解析一致。发起两个不同请求，确认日志、审批和 Shell 作用域不串请求。

受控审批验收可临时以 JVM 参数 `'-Dcommand.execution.policy.local-prompt[0]=echo'` 启动应用，让假模型或受控模型只请求 `echo task009-check`。分别验证：

1. 收到 `approval_required` 后，批准前命令不执行。
2. 将该事件的 `requestId`、`approvalId` 发送到 `POST /api/v1/chat_stream/{requestId}/approval`，请求体为 `{"approvalId":"...","decision":"approve_once"}`；批准后再次经过策略审查，只执行本次命令。
3. 新请求使用 `decision=reject`，应收到 `approval_resolved`，命令不执行，流中不应出现成功的绘图 `result`。
4. 审批等待期间断开流连接，待审批记录和该请求的本地 Shell 应清理；错误 requestId、重复决定和超时不能执行命令。

审批响应形状示例：

```json
{"code":"0000","data":{"requestId":"...","approvalId":"...","status":"rejected"}}
```

远程 Netty 命令需要单独的受控客户端环境；本地验收无需发送远程命令。后端目前没有认证，验收时只在受信网络运行。

## 5. 配置热更新与重启

`GET /api/v1/admin/query_current_agent_config` 返回当前配置；`POST /api/v1/admin/update_agent_config` 接收 `enabled` 和 `tables`。在隔离环境中提交一份有效配置，确认列表与对话使用同一新快照、旧会话失效。再提交包含未知工作流引用或非空旧 `plugin-name-list` 的无效配置，确认返回错误且原快照仍可查询/调用。最后重启应用，确认运行期更新不会持久化。

管理查询可能包含模型密钥。只在本机查看或保存经过脱敏的响应，不把原始响应粘贴到 issue、日志或 PR。

## 6. 交付记录

PR 中记录影响模块、执行过的命令及结果、未执行的真实模型/远程命令/容器验证项，并附脱敏的同步响应和流式事件顺序。若修改前端，另按 `data-visualizer-front/AGENTS.md` 执行 ESLint 和手动 UI 验证。
