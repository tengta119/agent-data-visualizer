# chat_stream 中间日志被拆成 1～2 个字符的问题

记录日期：2026-10-09。本文记录 PaiCLI 对话链路中的问题及本次修复，不涉及旧 Google ADK 日志插件。

## 1. 问题现象

向 `/api/v1/chat_stream` 提交绘图需求后，前端流式日志页出现大量独立日志卡片，每张卡片只有一两个字符，例如：

```text
##
需求
分析
结果
**
用户
意图
**
：
绘制
一个
```

换行、空格等片段也被作为日志发送，页面因此出现空白卡片；阶段开始事件的空字符串还会显示为“空内容”。这些日志属于同一阶段，例如 `agent_analyst`，本应组成连续正文。

影响是用户难以阅读中间分析，Markdown 标记与文字被拆散，日志消息和页面卡片数量显著增加。

## 2. 为什么会出现

模型流式返回的是增量文本 `delta`，它的边界由模型服务决定，并不保证是一句话、一行或固定数量的字符。一次回调只有一两个字符属于正常流式行为。

用户定位的调用：

```java
LlmClient.ChatResponse response = llmClient.chat(
        conversationHistory,
        toolExposure.definitions(),
        streamRenderer
);
```

其中 `streamRenderer` 接收模型增量。问题发生在后续日志适配：系统把每个正文增量直接当作一条可展示的完整日志，而没有进行聚合。

修复前的数据链路为：

```text
模型服务返回 content delta
  → AbstractOpenAiCompatibleClient 调用 onContentDelta(contentDelta)
  → Agent.StreamRenderer 调用 appendAssistantContentDelta(delta)
  → EmbeddedRenderer 发布 CONTENT_DELTA 事件
  → PaiCliWorkflowEngine.forward 添加阶段信息并转发
  → AgentServiceController.publishWorkflowEvent 直接发布 log
  → AgentStreamBridge 将每条 log 序列化并发送
  → 前端为每条 log 新建一张卡片
```

因此，“模型输出片段”与“用户可阅读的日志条目”之间缺少转换，是本次问题的根因。

## 3. 问题出在哪些代码

| 位置 | 行为与作用 |
| --- | --- |
| `data-visualizer-paicli/.../llm/AbstractOpenAiCompatibleClient.java` | 模型流读取后调用 `streamListener.onContentDelta(contentDelta)`，保留原始增量语义 |
| `data-visualizer-paicli/.../agent/Agent.java` 的 `StreamRenderer.onContentDelta()` | 将正文增量交给 `renderer.appendAssistantContentDelta(delta)` |
| `data-visualizer-paicli/.../embed/EmbeddedRenderer.java` | 每次增量生成一个 `EmbeddedEvent.Kind.CONTENT_DELTA`，只过滤 null 和空字符串，因此空白增量仍被转发 |
| `data-visualizer-domain/.../paicli/PaiCliWorkflowEngine.java` 的 `forward()` | 将嵌入事件映射为带阶段信息的工作流事件，没有聚合正文 |
| `data-visualizer-trigger/.../http/AgentServiceController.java` 原 `publishWorkflowEvent()` | **问题的主要适配位置**：除了阶段完成事件之外，所有事件的 `content` 都直接作为单条 `log` 发送 |
| `data-visualizer-front/src/features/chat/chat-stream-client.tsx` | 收到 `type=log` 后调用 `setLogs((current) => [...current, createLogItem(streamMessage)])`，将每条消息追加成独立日志条目 |

旧 Controller 的关键逻辑为：

```java
if (event.kind() == PaiCliWorkflowEvent.Kind.STAGE_COMPLETED) {
    agentStreamBridge.publishLog(context.requestId(), event.stage(), "stage completed");
} else {
    agentStreamBridge.publishLog(context.requestId(), event.stage(), event.content());
}
```

这段逻辑没有区分正文增量与状态、工具、阶段事件，导致 token 粒度直接成为日志粒度。前端按照既有日志协议逐条展示，使问题可见。

## 4. 如何修改

### 4.1 在工作流事件到 HTTP 日志之间增加聚合器

新增文件：

```text
data-visualizer-domain/src/main/java/top/lbwxxc/ai/domain/agent/service/chat/stream/WorkflowStreamLogPublisher.java
```

该类实现 `Consumer<PaiCliWorkflowEvent>`，接收原有工作流事件，通过 `BiConsumer<String, String>` 发布阶段与日志正文。

聚合规则：

1. 每次流式请求独立创建一个实例，避免不同请求共享缓存。
2. 使用 `pendingByStage` 按阶段分别缓存正文，避免不同阶段交错输出时拼接错乱。
3. 对 `CONTENT_DELTA` 追加缓存；遇到换行就发送完整行。
4. 无换行正文累计到 256 个 UTF-16 单元时分段发送，避免长 JSON/XML 一直等到模型结束才可见。若边界落在代理对中间，延后到低代理字符到达，单段可为 257 个单元。
5. 不发布纯空白段，保留非空正文内部的空格和换行。
6. 工具、状态、阶段事件到达时，先发送对应阶段剩余正文，再发送事件日志，保持该阶段的显示顺序。
7. 阶段开始与完成分别显示 `stage started`、`stage completed`；完成事件不重复发送整段最终正文。
8. 回调与刷新方法使用 `synchronized`，保护并行阶段访问同一请求聚合器时的缓存和发送顺序。

### 4.2 Controller 接入聚合器并刷新尾部

修改文件：

```text
data-visualizer-trigger/src/main/java/top/lbwxxc/ai/trigger/http/AgentServiceController.java
```

在流式异步任务中创建聚合器：

```java
WorkflowStreamLogPublisher logPublisher = new WorkflowStreamLogPublisher(
        (stage, content) -> agentStreamBridge.publishLog(currentRequestId, stage, content));
```

将 `logPublisher` 直接作为 `handleMessageStream()` 的事件监听器，替换原来的逐事件直发方法。

正常完成时，在发送 `result/done` 前调用 `logPublisher.flush()`；执行失败且请求尚未清理时，在发送 `error` 前刷新。这样没有换行、也未达到长度阈值的尾部正文不会遗漏。

请求取消或已清理时不补发缓存，沿用现有流式清理逻辑。聚合器仅在当前请求任务中存在，没有引入跨请求缓存或定时线程。

### 4.3 修复后的展示示例

模型依次回调 `"##"`、`" "`、`"需求"`、`"分析"`、`"结果"`、`"\n"`、`"\n"`，现在发送一条正文日志：

```text
## 需求分析结果
```

第二个纯换行段不生成空白卡片。后续正文继续按行或长度阈值发送。

底层模型回调仍保留增量语义；前端和 `log/result/error/done` 协议未修改，requestId 与 sessionId 关联也沿用原有 Bridge 实现。

## 5. 验证情况

新增 `WorkflowStreamLogPublisherTest`，覆盖：

- 多个短 token 合并为完整行，以及尾部刷新不重复发送。
- 纯空白增量与空状态不产生日志。
- 工具、状态、阶段事件之前刷新正文，阶段完成不重复最终正文。
- 交错阶段和不同请求之间的缓存隔离。
- 长行在结束前分段发送，以及跨 delta 的 Unicode 代理对不被切断。

扩展 `PaiCliControllerContractTest`，覆盖正常结果与错误消息之前的正文刷新、日志顺序和 requestId 关联。

本次修复已通过 `git diff --check`。按照仓库要求，未执行构建、JUnit 或页面运行验证；上述测试已经补充，尚未运行。

开发人员手动验证时，可提交会产生多行分析的绘图需求，确认日志按行显示、没有纯换行空白卡片；再检查较长无换行输出能够分段出现，并在成功或失败时收到最后一段正文。

## 6. 当前边界与文档处理

当前采用换行、长度阈值及事件边界触发发送，没有定时刷新。较短且没有换行的正文，会等到后续工具/状态/阶段事件或请求结束时才显示；模型最终回答的内容没有因此改变。

此次修复已在 `docs/architecture.md` 的流式对话部分，以及 `docs/business.md` 的执行可见性部分记录。它是现有日志展示行为的局部修正，没有新增存储、协议或部署架构，不新增 ADR。本次仅补充问题记录，无需再次修改这两份文档。
