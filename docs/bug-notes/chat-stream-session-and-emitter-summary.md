# chat_stream Session 与异步推送问题总结

## 1. Session not found 的根因

### 现象

`/api/v1/chat_stream` 请求时，控制器日志里已经打印出了 `sessionId`，但执行 `runner.runAsync(...)` 时仍然报错：

```text
Session not found: xxx for user admin
```

### 根因

问题不在前端是否提前创建了 `sessionId`，而在于“创建 Session 的 runner”和“执行对话的 runner”不是同一个会话仓库。

旧实现中：

1. `ChatService.createSession()` 使用注册时保存的原始 `InMemoryRunner` 创建 Session。
2. `ChatService.handleMessageStream()` 为了临时挂载 `MyLogPlugin`，重新 `new InMemoryRunner(...)`。
3. 新创建的 `InMemoryRunner` 内部使用新的 in-memory session store。
4. 因此旧 runner 创建的 `sessionId` 无法在新 runner 中查到。

### 修复方式

不要重新 `new InMemoryRunner(...)`，而是基于原始 runner 使用 `Runner.builder()` 重建请求级 runner，并复用：

- `sessionService()`
- `artifactService()`
- `memoryService()`
- `agent()`
- `appName()`

这样请求级 runner 和原始 runner 使用的是同一套 Session 仓库。

### 额外修正

`createSession()` 原来只按 `userId` 做缓存，容易导致同一用户切换不同 agent 时复用错误的 session。现在改为按 `agentId:userId` 作为缓存 key。

另外，`AgentServiceController.chatStream()` 中不能无条件：

```java
requestDTO.setSessionId(chatService.createSession(...))
```

否则会覆盖前端已经传入的 `sessionId`。正确做法是仅在 `sessionId` 为空时创建。

## 2. MyLogPlugin 已执行但前端没有收到日志

### 现象

后端日志中已经能看到：

- `MyLogPlugin` 输出的业务日志
- `LoggingPlugin` 输出的生命周期日志

说明插件确实已经成功执行，但前端流式测试页没有实时显示 `log` 消息。

### 根因

`ResponseBodyEmitter` 并没有真正进入“先返回、后异步写入”的工作模式。

旧实现中，控制器在当前 HTTP 请求线程里直接执行：

```java
chatService.handleMessageStream(...).subscribe(...)
```

这会导致：

1. `subscribe(...)` 在当前请求线程内跑完整个 AI 流。
2. `chatStream()` 方法在流执行结束前无法及时 `return emitter`。
3. 浏览器端没有真正建立起异步流式消费。
4. 即使 `AgentStreamBridge.publish(...)` 已经调用了 `emitter.send(...)`，前端也无法及时收到过程日志。

### 修复方式

将主流订阅放到异步线程中执行，例如：

```java
CompletableFuture.runAsync(() -> {
    chatService.handleMessageStream(...).subscribe(...);
});
```

这样控制器会先返回 `ResponseBodyEmitter`，后端再在后台线程中持续写入：

- `type=log`
- `type=result`
- `type=done`
- `type=error`

前端才能真正实时收到日志。

## 3. 当前稳定实现的原则

1. `sessionId` 只在缺失时创建，不覆盖前端已传值。
2. 请求级日志插件可以临时挂载，但必须复用原 runner 的 `sessionService`。
3. `ResponseBodyEmitter` 必须“先返回，再异步写入”。
4. `MyLogPlugin` 只负责通过 `AgentStreamBridge` 发送 `log/error`。
5. 最终结果由控制器在主流完成时统一发送 `result/done`。

## 4. 排查同类问题的优先顺序

1. 看控制器日志里 `sessionId` 是否为空。
2. 看 `createSession()` 和 `runAsync()` 是否使用同一套 `sessionService()`。
3. 看 `chatStream()` 是否在主线程里直接 `subscribe(...)`。
4. 看 `ResponseBodyEmitter` 是否已经先返回，再由后台线程写入。
5. 看 `AgentStreamBridge` 是否成功注册了当前 `requestId -> emitter`。
