# 面试 Q&A：流式推送（ResponseBodyEmitter）/ 跨请求审批唤醒（D6–D7）

> 依据：`docs/architecture.md`、`docs/business.md` 及源码（`AgentServiceController`、`AgentStreamBridge`、`CommandApprovalService`、`PendingCommandApproval`、`CommandExecutionPolicyProperties`、`ShellExecutor`）。
> 所有回答基于文档描述的完整设计，不编造项目事实。

---

## ⚠️ 面试 / 演示前必须处理

与 `QA-D1-D5` 文档中标注的调试中间态相同，当前工作区仍存在（本次已再次核实）：

- `ShellExecutor.execute()` 中 `review.isForbidden()` 快速拒绝和批准后的二次策略复核（`reReview.isForbidden()`）被注释，且 `if (true)` 强制**所有**命令走审批；
- `CommandPolicyReviewer` 末尾 `promptMatched = true;` 无条件覆盖，ALLOW 分支不可达。

D7 的回答按"策略审查在前、Forbidden 不可被审批绕过、批准后二次复核"的完整设计讲述；演示前请恢复代码，否则面试官现场读代码会发现讲的和跑的对不上。

---

## D6. ResponseBodyEmitter：为什么拿到对象就能"何时何地"发数据？

**Q：你项目里流式接口用的是 Spring MVC 的 ResponseBodyEmitter，Controller 返回这个对象之后，Agent 还在后台跑，日志还在陆续推。这个异步 API 的底层原理是什么？为什么拿到这个对象，可以在任意时间、任意线程向客户端传输数据？**

### A：面试回答

先说结论：**能"随时随地的发数据"，本质是因为 HTTP 连接的存活期和处理请求的线程被解耦了，而这个对象本身就是一个指向这条活连接的写入口的引用。**

先说背景。传统的 Servlet 模型是"一个请求一个线程同步走完"——Controller 返回的那一刻，Tomcat 就认为响应结束了，会回收 Request 和 Response 对象，连接的去留也由容器按它的规则决定。我的项目里 Agent 一次执行可能跑几分钟，如果走传统模式，一个 HTTP 工作线程就要被占满几分钟，并发一上来线程池就打满了。

所以我的流式接口用的是 `ResponseBodyEmitter`。它的底层是 Servlet 3.0 的异步机制：Controller 返回这个对象的时候，Spring 内部会调用 `request.startAsync()`，相当于告诉 Tomcat——**"这次处理还没结束，别因为方法返回了就关响应"**。这样即使 Tomcat 线程回池子去服务别的请求了，这条 TCP 连接依然由容器保管着，是活的。

而 emitter 这个对象，它内部持有的是这条连接的响应输出流的引用。调用它的 `send` 方法，就是把数据序列化之后 write 加 flush 到那个 socket 上，浏览器马上就能收到。因为响应开始时不知道总长度，所以走的是 chunked 编码——每 send 一次就推一个块出去，我前端就是按花括号边界增量解析这些连续 JSON 的。

至于"随时随地"，就是对象引用传递的问题了。我把每个 emitter 按请求 ID 注册到一个全局单例的桥接组件里，这样不管是后台执行 Agent 的线程、Agent 框架回调日志的线程，还是用户提交审批决定时那个**完全独立的 HTTP 请求线程**，只要拿请求 ID 就能从桥接组件里取到同一个 emitter，往同一条连接写数据。连接是归容器管的，不归任何线程管，所以谁持有引用谁就能写。

最后是这个方案的边界，我在代码里专门处理过几个坑。一是 `complete` 之后再 send 会抛异常，所以桥接组件里我加了"完成标志 + 每请求一把锁"，把"检查是否完成 → 发送"变成原子操作，避免清理和发送的竞态；二是客户端断连只有在你下次写入时才能感知到，所以清理逻辑要靠超时和写入失败兜底，而且要做成幂等的，我在完成、超时、异常、断连四条路径上用了 CAS 保证只清理一次。另外要诚实地说一点：这个方案的线程占用只是从 Tomcat 线程转移到了后台线程池，并没有消灭——我目前用的是 `CompletableFuture.runAsync` 的默认线程池，这块如果上生产是要换成自定义线程池的。

### 技术底稿（供深挖追问时备查）

#### 一句话结论

`ResponseBodyEmitter` 能"何时何地"发数据，本质是两件事的结合：**Servlet 3.0 异步模式把"HTTP 连接的生命周期"和"处理请求的工作线程"解耦**，而 **emitter 对象本身只是持有一个指向这条活连接的写入口的引用**——谁拿到这个引用，谁就能往那个 socket 写字节。

#### 1. 传统模型的问题（为什么需要它）

传统 Servlet 请求处理是"一个请求一个线程，同步走完"：

```
Tomcat worker 线程: 接收请求 → 调 Controller → 拿到返回值 → 序列化写响应 → 关闭/回收 response → 线程回池
```

隐含约定是：**Controller 方法返回的那一刻，响应就该结束了**。Tomcat 随后会回收 `Request`/`Response` 对象（对象池复用）并决定连接的去留。

Agent 要跑 5 分钟，Controller 方法就得阻塞 5 分钟——这正是本项目 `/api/v1/chat` 的做法（`blockingForEach` 阻塞请求线程，架构文档风险 #5）。流式接口不能这么干，所以才用 `ResponseBodyEmitter`。

#### 2. Servlet 3.0 异步：连接与线程解耦

关键 API 是 `request.startAsync()`，它做了两件事：

1. 返回一个 `AsyncContext`，**把当前请求和响应对象"钉住"**——即使 Servlet 方法返回了，Tomcat 也不回收这对 `Request`/`Response`，底层 TCP 连接保持打开；
2. 告诉容器：**这次处理还没结束，不要因为我返回了就关闭响应**。

也就是说，异步模式下：

- **连接（socket）归属容器，不归属任何线程**；
- 工作线程处理完入口逻辑就回池子，去服务别的请求；
- 之后任何时候、任何线程，只要能拿到那个 response 的输出流引用并 write + flush，数据就会顺着这条还活着的连接发给客户端。

这就是"何地"（任意线程）的来源。`AsyncContext.complete()`（或超时/出错）才是真正终结响应、让 Tomcat 回收对象的时刻。

#### 3. Spring MVC 对 emitter 的装配链路

Controller 返回 `ResponseBodyEmitter` 时，`RequestMappingHandlerAdapter` 里注册的 **`ResponseBodyEmitterReturnValueHandler`** 接管返回值，大致做四件事：

```java
// 伪代码，对应 Spring 真实流程
1. emitter.extendResponse(response);          // 设置超时（构造时传的 20min）等
2. HttpMessageConvertingHandler handler =
       new HttpMessageConvertingHandler(serverHttpResponse, deferredResult);
                                             // 这个 handler 持有 response 的写入口，
                                             // send(obj) = HttpMessageConverter 序列化 → write → flush
3. emitter.initialize(handler);               // 把 handler 注入 emitter
4. webAsyncManager.startDeferredResultProcessing(deferredResult, mavContainer);
                                             // 内部调 asyncWebRequest.startAsync()
                                             //   → request.startAsync()（第 2 节说的那个）
```

然后 `DispatcherServlet.doDispatch` 检查到 `asyncManager.isConcurrentHandlingStarted() == true`，**直接 return**，不渲染视图、不写响应。Tomcat 线程就此释放。

`emitter.complete()` / `completeWithError()` 走反向链路：完成内部 `DeferredResult` → 触发 async dispatch / `AsyncContext.complete()` → 容器终结响应、回收对象、按 keep-alive 策略处理连接。

#### 4. emitter 对象内部：为什么"何时"都行

`ResponseBodyEmitter` 的核心字段与 `send` 逻辑（Spring 源码简化）：

```java
public class ResponseBodyEmitter {
    private Handler handler;                                        // initialize() 后被注入，指向活连接
    private final List<DataWithMediaType> earlySendAttempts = ...;  // 初始化前的缓冲
    private boolean complete = false;

    public synchronized void send(Object object, MediaType mediaType) {
        Assert.state(!this.complete, "ResponseBodyEmitter has already completed");
        if (this.handler == null) {
            this.earlySendAttempts.add(...);   // 还没 initialize？先存起来
            return;
        }
        this.handler.send(object, mediaType);  // 直接 write + flush 到 socket
    }
}
```

三个要点：

1. **`send()` 是 `synchronized` 的**——Spring 在 emitter 实例上串行化写入，多线程并发 `send` 不会把字节流写交叉；
2. **初始化前的 send 会被缓冲**（`earlySendAttempts`），initialize 时重放——理论上 Controller 返回前就 send 也不丢；
3. **`complete` 之后 send 抛 `IllegalStateException`**——连接已终结，这就是桥接组件要先查完成标志的原因。

由于响应开始时 Content-Length 未知，容器用 **HTTP/1.1 chunked 编码**：每次 `send + flush` 产生 chunk 送达浏览器。这正好匹配前端 `consumeJsonMessages` 按花括号边界增量解析"连续 JSON 对象"的设计——不用 `EventSource`，就是纯 chunked HTTP 响应体的增量读取。

#### 5. 本项目的落地："何时何地"具体怎么实现

```java
ResponseBodyEmitter emitter = new ResponseBodyEmitter(20 * 60 * 1000L);
...
agentStreamBridge.register(currentSessionId, currentRequestId, emitter);  // ① 把"连接引用"交给全局单例保管
...
CompletableFuture.runAsync(() -> { ... });   // ② Agent 在别的线程跑，Controller 立即 return emitter
return emitter;                              // ③ Tomcat 线程释放
```

- **"何地"的实现**：`AgentStreamBridge` 是 `@Service` 单例，内部 `ConcurrentHashMap<requestId, StreamEmitterContext>` 持有所有活跃连接的引用。三类完全不同来源的线程都能推送：
  - `CompletableFuture.runAsync` 的 common pool 线程里，RxJava 的事件回调（`subscribe(event -> bridge.publish(...))`）；
  - ADK/RxJava 自己的调度线程上的 `MyLogPlugin` 日志；
  - 审批链路：`decideChatStreamApproval` 是**另一个完全独立的 HTTP 请求线程**，经 `ApplicationEventPublisher` 同步事件唤醒监听器，监听器再往**第一条连接**写 `approval_resolved`。

  三条线程互不相识，但都通过 `requestId` 从 Bridge 拿到同一个 emitter → 同一个 socket。**"何地" = 对象引用传递 + 连接归属容器而非线程**。

- **"何时"的实现**：Tomcat 线程返回后，`AsyncContext` 让连接存活最长 20 分钟（构造时传的 timeout）。期间任意时刻 `send` 都会立刻 flush 给浏览器；超时触发 `WebAsyncManager` 的超时任务 → `emitter.onTimeout(cleanupStream)` → 清理审批、dispose 订阅、从 Bridge 移除。

- **超出 Spring 默认保证的并发防护**（`AgentStreamBridge`）：

  ```java
  synchronized (context.sendLock) {
      if (context.completed.get()) return;   // check-then-send 原子化，
      context.emitter.send(...);             // 避免 clear() 竞态后 send 抛 IllegalStateException
  }
  ```

  Spring 只保证单个 `send` 调用不交叉，但**"检查是否完成 → 发送"这个复合操作不是原子的**。Bridge 用 per-request 锁 + `AtomicBoolean` 补上这个缝隙，并保证 `cleanupStream` 在 completion/timeout/error/断连四条路径上幂等（`AtomicBoolean.compareAndSet`）。

#### 6. 边界与代价

| 边界 | 说明 |
| --- | --- |
| `complete()` 后不能再 send | 抛 `IllegalStateException`；Bridge 在异常时先置 `completed` 再上抛转 `RuntimeException` |
| 客户端断连的感知是**写入时** | 只有下次 write 碰到 broken pipe 才触发 `onError`/`onCompletion`；不发数据就不知道用户早走了——清理靠超时和写入失败兜底 |
| 异步 ≠ 后台任务被取消 | 用户点"停止"只结束浏览器侧读取并触发服务端 emitter 清理 + `dispose()`，模型调用的底层执行是否真正取消无法由代码保证 |
| 占用的线程只是换了地方 | Tomcat 线程省下了，但 `runAsync` 用的是 JDK common pool（架构风险 #4），且 Prompt 审批等待最多占该线程 60s——异步化转移了线程占用，没有消灭它 |
| 单机内存状态 | emitter 引用、requestId 映射都在 JVM 内，多实例/重启即断——这是"何地"能跨越线程、但不能跨越进程的原因 |

#### 核心心智模型

> Servlet 3.0 的 `startAsync()` 把 TCP 连接的存活期从"请求处理线程的生命周期"中剥离出来，交给容器的 `AsyncContext` 保管；`ResponseBodyEmitter` 只是一个持有该连接写入口（经 `HttpMessageConverter` 序列化后 write+flush）的普通 Java 对象。把它注册到单例的桥接组件，任何线程凭 requestId 都能拿到这个引用并写入——这就是"何时何地"的原理。代价是：生命周期管理（超时、断连、幂等清理、complete 后的边界）全部变成自己的责任，Spring 只提供了 `synchronized send` 和回调钩子。

### 回答背后的核心逻辑

- **一句话锚定原理**（连接与线程解耦 + 对象即连接引用），让面试官不需要任何项目背景就能接住；
- 因果顺序：传统模型为什么不行 → Servlet 3.0 异步怎么解决 → emitter 就是写入口引用 → 引用传递实现跨线程 → 最后主动交代边界，把架构文档里已记录的 common pool 风险作为"诚实的缺陷"说出来，而不是藏起来；
- 项目内部名词做了降维：`AgentStreamBridge` → "全局单例的桥接组件"，`MyLogPlugin` → "Agent 框架回调日志的线程"，只在必要处保留 `ResponseBodyEmitter` 这个 Spring 通用 API 名。

### 面试官可能继续追问

1. **"你说 write 加 flush 就能推给浏览器，那浏览器是怎么区分一条消息结束的？"** → chunked 编码，每个 chunk 自带长度边界；但业务层设计的是连续 JSON 对象流，前端按花括号配平增量解析，所以即使一条 JSON 被拆在两个 chunk 里也不会解析错。

2. **"多个线程同时往一个 emitter 写，不会把数据写串吗？"** → 两层保护：Spring 的 `send` 方法本身是 synchronized 的，保证单次写入字节不交叉；但"先检查完成状态再写"这个复合动作不是原子的，所以桥接组件里加了 per-request 锁加一个完成标志位，把检查和写入锁在一个临界区里。

3. **"客户端中途把页面关了，服务端怎么知道的？模型调用会停吗？"** → 感知是滞后的：连接断了只有在下一次写入碰壁时才触发异常，进而走 onError/onCompletion 回调做清理。清理会取消订阅，但模型那一侧的底层 HTTP 调用是否真正中断，不能保证——设计上就承认了，不做虚假承诺。

4. **"和 SSE（SseEmitter）、WebSocket 比你为什么选这个？"** → 需要的是服务端单向推送、HTTP 语义就够了；SSE 的 `EventSource` 只支持 GET 且断线重连语义和"请求-响应"模式不匹配；WebSocket 是双向全双工，对这个场景过重，还要单独维护连接协议。emitter 是 POST + 普通 HTTP 响应体，前端用 Fetch 的 ReadableStream 读，最简单也够用。

5. **"超时设了多久？超时之后在途的请求怎么办？"** → 20 分钟，是给慢模型和交互审批留的窗口。超时回调里做统一清理：取消还处于待定状态的审批、取消订阅、从桥接组件移除引用，全程幂等。

### 如果面试官继续深入

**追问："`startAsync` 之后 Tomcat 内部到底发生了什么？"**

> Servlet 容器对每个连接有一个状态机。正常流程里，Servlet service 方法返回后容器就进入"响应完成"阶段，回收这对 Request/Response（池化对象）然后处理连接的 keep-alive。调用 `startAsync()` 会把这次处理标记为异步挂起，容器跳过完成阶段，把这对对象交给 `AsyncContext` 保管，同时挂一个超时任务（时间就是从 emitter 传进去的 20 分钟）。之后任何线程写这个 Response 的输出流都有效，因为对象没被回收。真正终结要等到 `AsyncContext.complete()`，或者超时/出错——emitter 的 `complete()` 最终就是走这条链，中间经过 Spring 的异步管理器做一次结果回调。超时清理钩子就是挂在 Spring 的 `WebAsyncManager` 超时链路上的。

**追问："审批那条链路具体怎么把数据写到第一条连接上的？"**

> 见 D7。

**追问："如果让你改造，你会怎么解决单机和线程池的问题？"**

> 线程池方面，把 `runAsync` 换成项目里已经配置好的有界线程池，拒绝策略和队列上限就能生效；审批等待那 60 秒也不应该占用执行线程，可以拆成"工具线程挂起 → 事件唤醒"的模型（当前实现已经是 Future 等待，但等待仍发生在执行线程上）。单机方面，如果要上多实例，思路是连接亲和：请求路由到固定实例，跨实例的通知走消息广播，每台实例自己判断该连接在不在这台机器上。但这些都是规划，当前实现就是单机内存态，不会把它说成已经支持的水平。

---

## D7. 跨请求审批唤醒：两条 HTTP 请求、两个线程之间怎么关联、怎么唤醒、怎么只生效一次？

**Q：你的 Agent 工具线程在等用户审批命令，但用户的决定是通过另一个独立的 HTTP 接口提交的——这两条请求、两个线程之间你怎么建立关联？等待中的线程怎么被唤醒？同一个审批被重复提交怎么办？怎么保证用户批的就是被执行的那条命令？**

### A：面试回答

先说场景。Agent 执行过程中模型可以调用 shell 工具，某些命令按策略需要用户确认后才能执行。此时 Agent 的工具线程会"卡"在那里等用户点头，而用户的决定是通过另一个独立的 HTTP 接口提交的——两条请求、两个线程，互相不认识。这就是一个典型的跨请求、跨线程协作问题。

关联靠两层 ID。第一层是 requestId，标识这一次流式对话，工具线程的执行上下文里带着它，前端提交审批时也带上；第二层是 approvalId，每次需要审批的命令生成一个，绑定 requestId、命令类型、目标主机和命令摘要。前端两个都带过来，服务端就能唯一定位到那一条待审批记录。

等待和唤醒用的是**每条审批记录一个独立的 CompletableFuture**。工具线程创建审批记录后，在它自己的 future 上限时等待，默认 60 秒；用户提交决定后，那个 HTTP 请求线程只做两件事：发布一个进程内的同步事件，监听器去完成这条 future。future 一完成，工具线程就被唤醒，拿到终态继续往下走。我没有用全局锁或者 wait/notify，因为审批记录是请求级的，等待的粒度也应该是请求级的。

只生效一次靠 CAS 状态机。每条记录的状态是 PENDING → APPROVED / REJECTED / EXPIRED / CANCELLED，迁移用 AtomicReference 的 compareAndSet——只能从 PENDING 迁移一次，第二次提交、超时、取消不管谁先到，都只有一个赢家。future 的完成也是在这同一个 CAS 里做的，所以状态和唤醒不会脱节。重复提交会拿到"已决定"的结果，但改变不了终态。

怎么保证批的就是被执行的那条命令？批准之后我不直接执行，还要做二次复核：审批记录还存在、还是 APPROVED 状态、requestId 匹配、**命令摘要一致**。摘要是命令快照的哈希，创建审批时就固定下来了，所以前端不能借审批接口改命令内容——批的是 A 命令，执行的也必须是 A。

边界要诚实说：审批表和等待都在单机内存里，重启丢失、多实例不共享；用户断开流式连接时，未决审批统一转 CANCELLED 并唤醒等待线程，不泄漏；当前系统没有后端认证，拿到两个 ID 的人就能提交决定，所以它只是"交互确认"，不是授权。

### 回答背后的核心逻辑

面试官在考跨线程协作的三个子问题：**线程间怎么传数据**（ID 关联 + 共享内存对象）、**怎么唤醒**（Future）、**怎么保证并发正确性**（CAS 一次性状态迁移）。答案的三件套——CompletableFuture 等待唤醒、AtomicReference CAS、digest 快照校验——正好一一对应"怎么等、怎么保证一次、怎么保证批的和执行的是同一条命令"。另外"为什么用同步 ApplicationEvent 而不是 MQ"也是隐藏考点：进程内一次唤醒，同步事件是最简单且语义正确的工具，引 MQ 反而是错的。

### 面试官可能继续追问

1. **"为什么用 Spring 事件而不是 Controller 直接调 service？"** → Controller 只做参数校验和响应组装，决定的处理收敛在领域层监听器里。同步事件在发布线程上执行，语义上就是一次进程内方法调用，只是解耦了 HTTP 入口和领域逻辑。不需要 MQ 的持久化、跨实例语义，引了反而是错误工具。

2. **"工具线程最多等多久？超时了怎么办？"** → 默认 60 秒、上限 300 秒，可配。等待超时后把状态 CAS 成 EXPIRED，命令不执行，Agent 拿到"审批未完成"的结果继续往下走，整条流不会因此挂死。

3. **"用户直接关掉页面怎么办？"** → 流式连接的完成、超时、异常回调统一触发按 requestId 的取消：PENDING 的记录转 CANCELLED、完成 future 唤醒等待线程、从注册表移除。这个取消和用户提交决定走的是同一把 CAS，不冲突。

4. **"Controller 发布完事件为什么还要再查一次结果？"** → 事件是同步执行的，发布返回时状态迁移已经完成；再查是为了给前端组装 approved / already_resolved / not_found 这类只读结果，而不是靠事件返回值传数据。查询和迁移分离，也避免了 Controller 直接碰等待中的 Future。

5. **"重复提交、错误的 approvalId 会怎样？"** → 重复提交返回 already_resolved，终态不回退；错误 ID 返回 not_found；requestId 对不上返回 request_mismatch。每条记录独立，互不影响。

### 如果面试官继续深入

**追问："为什么不直接 wait/notify，或者用一个全局的 CountDownLatch？"**

> wait/notify 要求手写全部协调逻辑，容易漏 notify、还要处理虚假唤醒；全局锁或全局门闩会让不同请求的审批互相阻塞，还跟"记录被清理"存在竞态。CompletableFuture 把"限时等待 + 单次完成 + 异常传播"封装好了，每条记录一个，天然请求级隔离。`get(timeout)` 抛 TimeoutException 我捕获后本地迁移 EXPIRED；InterruptedException 恢复中断位后转 CANCELLED；事件发送失败时也会立刻取消记录，不会让工具线程白等。

**追问："多实例要怎么改？"**

> 两件事：审批记录要放到共享存储，或者按 requestId 路由到持有那条流式连接的实例；决定事件要能跨实例投递，比如消息广播。但更根本的问题是连接亲和——审批请求是推到那条流式连接上的，决定的结果也要回到那条连接，所以实例间要么粘住路由，要么做转发。当前设计明确就是单机的，不过度设计。

**追问："这个审批能被绕过吗？"**

> 按设计有三道防线：策略审查在前，Forbidden 的命令不可被审批绕过；审批状态机只能从 PENDING 迁移一次；执行前还有摘要 + requestId + 状态的二次复核。但代码层面真正的缺口是入口没有认证——requestId / approvalId 是"知道即可用"的凭据，不是身份。所以结论是：**防误用、防并发错乱它是完备的；防恶意调用方它不是**，那需要先有服务端身份体系。（注意：当前工作区代码中 Forbidden 快速拒绝与二次复核被注释为调试中间态，演示前需恢复，见文档顶部警示。）
