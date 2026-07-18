# API 测试目录知识点与示例代码

来源目录：

`ai-agent-scaffold-lite-app/src/test/java/cn/bugstack/ai/test/api`

该目录主要包含三类示例：大模型 API 接入、MCP 工具调用、Agent 编排。

## 1. Spring AI 调用大模型

涉及文件：

- `model/SpringAiApiTest.java`

知识点：

- 使用 Spring AI 的 `ChatModel` 调用 OpenAI 兼容接口。
- 通过 `OpenAiApi` 配置 `baseUrl`、`apiKey`、`completionsPath`、`embeddingsPath`。
- 通过 `OpenAiChatOptions` 指定模型名称。

示例代码：

```java
OpenAiApi openAiApi = OpenAiApi.builder()
        .baseUrl("https://api.deepseek.com")
        .apiKey("你的 API Key")
        .completionsPath("v1/chat/completions")
        .embeddingsPath("v1/embeddings")
        .build();

ChatModel chatModel = OpenAiChatModel.builder()
        .openAiApi(openAiApi)
        .defaultOptions(OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .build())
        .build();

String result = chatModel.call("hi 你好哇!");
System.out.println(result);
```

## 2. LangChain4j 调用大模型

涉及文件：

- `model/LangChain4jApiTest.java`

知识点：

- 使用 LangChain4j 的 `OpenAiChatModel` 调用 OpenAI 兼容接口。
- 使用 `model.chat(...)` 完成一次对话调用。

示例代码：

```java
OpenAiChatModel model = OpenAiChatModel.builder()
        .baseUrl("https://apis.itedus.cn/v1")
        .apiKey("你的 API Key")
        .modelName("gpt-4o")
        .build();

String answer = model.chat("hi 你好哇!");
System.out.println(answer);
```

## 3. Spring AI 接入 MCP 工具

涉及文件：

- `tool/SpringAiToolTest.java`
- `agent/SequentialAgentTest.java`

知识点：

- 使用 MCP 给大模型扩展外部工具能力。
- 使用 `HttpClientSseClientTransport` 连接 SSE 类型 MCP 服务。
- 使用 `McpClient.sync(...)` 创建同步 MCP 客户端。
- 使用 `SyncMcpToolCallbackProvider` 把 MCP 工具注册到 Spring AI 模型。

示例代码：

```java
HttpClientSseClientTransport transport =
        HttpClientSseClientTransport.builder("https://your-mcp-server/")
                .sseEndpoint("sse")
                .build();

McpSyncClient mcpClient = McpClient.sync(transport)
        .requestTimeout(Duration.ofMinutes(5))
        .build();

mcpClient.initialize();

ChatModel chatModel = OpenAiChatModel.builder()
        .openAiApi(openAiApi)
        .defaultOptions(OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .toolCallbacks(
                        SyncMcpToolCallbackProvider.builder()
                                .mcpClients(mcpClient)
                                .build()
                                .getToolCallbacks()
                )
                .build())
        .build();

String answer = chatModel.call("从北京西站到北京站，坐地铁大概需要多久？");
System.out.println(answer);
```

## 4. LangChain4j 接入 MCP 工具

涉及文件：

- `tool/LangChain4jToolTest.java`

知识点：

- LangChain4j 可通过 `AiServices` 创建接口代理。
- 使用 `.tools(mcpClient)` 将 MCP 工具注入 AI 服务。
- 使用 `MessageWindowChatMemory` 保存短期会话记忆。

示例代码：

```java
interface Assistant {
    String chat(String message);
}

McpSyncClient mcpClient = McpClient.sync(transport)
        .requestTimeout(Duration.ofMinutes(5))
        .build();

mcpClient.initialize();

Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(model)
        .tools(mcpClient)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
        .build();

String answer = assistant.chat("你有哪些工具能力？");
System.out.println(answer);
```

## 5. LangChain4j AI Service 接口代理

涉及文件：

- `tool/LangChain4jToolTest.java`

知识点：

- 通过 Java 接口定义 AI 服务能力。
- 使用 `AiServices.builder(Assistant.class)` 创建代理对象。
- 调用方式类似普通 Java 方法。

示例代码：

```java
interface Assistant {
    String chat(String message);
}

OpenAiChatModel model = OpenAiChatModel.builder()
        .baseUrl("https://apis.itedus.cn/v1")
        .apiKey("你的 API Key")
        .modelName("gpt-4o-mini")
        .build();

Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(model)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
        .build();

String answer = assistant.chat("请介绍一下 Java 中的接口");
System.out.println(answer);
```

## 6. LlmAgent 单智能体

涉及文件：

- `agent/SequentialAgentTest.java`
- `agent/ParallelAgentTest.java`
- `agent/LoopAgentTest.java`

知识点：

- 使用 `LlmAgent` 定义一个独立智能体。
- 通过 `name`、`description`、`instruction` 定义智能体身份和行为。
- 通过 `outputKey` 将输出写入上下文状态。

示例代码：

```java
LlmAgent agent = LlmAgent.builder()
        .name("CodeWriterAgent")
        .description("根据用户需求生成 Java 代码")
        .model(new SpringAI(chatModel))
        .instruction("""
                You are a Java Code Generator.
                Based only on the user's request, write Java code.
                Output only the Java code.
                """)
        .outputKey("generated_code")
        .build();
```

## 7. SequentialAgent 顺序编排

涉及文件：

- `agent/SequentialAgentTest.java`

知识点：

- 使用 `SequentialAgent` 串行执行多个子 Agent。
- 前一个 Agent 的输出可以作为后一个 Agent 的输入。
- 适合代码生成、审查、重构等流水线任务。

示例代码：

```java
SequentialAgent codePipelineAgent = SequentialAgent.builder()
        .name("CodePipelineAgent")
        .description("依次执行代码生成、代码审查、代码重构")
        .subAgents(
                codeWriterAgent,
                codeReviewerAgent,
                codeRefactorerAgent
        )
        .build();
```

典型流程：

```text
用户需求 -> CodeWriterAgent -> CodeReviewerAgent -> CodeRefactorerAgent -> 最终代码
```

## 8. Agent 状态传递：outputKey

涉及文件：

- `agent/SequentialAgentTest.java`
- `agent/ParallelAgentTest.java`
- `agent/LoopAgentTest.java`

知识点：

- `outputKey` 用于把某个 Agent 的输出写入状态。
- 后续 Agent 可以通过 `{generated_code}`、`{review_comments}` 等变量读取状态。

示例代码：

```java
LlmAgent writerAgent = LlmAgent.builder()
        .name("CodeWriterAgent")
        .model(new SpringAI(chatModel))
        .instruction("根据用户需求生成 Java 代码")
        .outputKey("generated_code")
        .build();

LlmAgent reviewerAgent = LlmAgent.builder()
        .name("CodeReviewerAgent")
        .model(new SpringAI(chatModel))
        .instruction("""
                请审查下面的代码：

                ```java
                {generated_code}
                ```

                只输出审查意见。
                """)
        .outputKey("review_comments")
        .build();
```

## 9. ParallelAgent 并行编排

涉及文件：

- `agent/ParallelAgentTest.java`

知识点：

- 使用 `ParallelAgent` 并行执行多个互不依赖的子任务。
- 每个子 Agent 将结果写入不同的 `outputKey`。
- 最后可以使用汇总 Agent 统一整理结果。

示例代码：

```java
ParallelAgent parallelResearchAgent = ParallelAgent.builder()
        .name("ParallelWebResearchAgent")
        .description("并行执行多个研究任务")
        .subAgents(
                renewableEnergyAgent,
                evTechnologyAgent,
                carbonCaptureAgent
        )
        .build();
```

汇总 Agent：

```java
LlmAgent mergerAgent = LlmAgent.builder()
        .name("SynthesisAgent")
        .model(new SpringAI(chatModel))
        .instruction("""
                请基于下面三个研究结果生成报告：

                Renewable Energy:
                {renewable_energy_result}

                Electric Vehicles:
                {ev_technology_result}

                Carbon Capture:
                {carbon_capture_result}

                只能使用上面提供的信息。
                """)
        .build();

SequentialAgent pipeline = SequentialAgent.builder()
        .name("ResearchAndSynthesisPipeline")
        .subAgents(parallelResearchAgent, mergerAgent)
        .build();
```

## 10. LoopAgent 循环编排

涉及文件：

- `agent/LoopAgentTest.java`

知识点：

- 使用 `LoopAgent` 反复执行一组子 Agent。
- 适合“生成-批评-修改”这类迭代优化任务。
- 通过 `maxIterations` 防止无限循环。

示例代码：

```java
LoopAgent refinementLoop = LoopAgent.builder()
        .name("RefinementLoop")
        .description("反复批评和修改文档，直到完成")
        .subAgents(criticAgent, refinerAgent)
        .maxIterations(5)
        .build();

SequentialAgent writingPipeline = SequentialAgent.builder()
        .name("IterativeWritingPipeline")
        .subAgents(initialWriterAgent, refinementLoop)
        .build();
```

执行逻辑：

```text
InitialWriterAgent 生成初稿
        ↓
CriticAgent 审查
        ↓
RefinerAgent 修改
        ↓
如果未完成，继续循环
如果完成，调用 exitLoop 退出
```

## 11. FunctionTool 本地函数工具

涉及文件：

- `agent/LoopAgentTest.java`

知识点：

- Java 静态方法可以暴露为 Agent 工具。
- `@Annotations.Schema` 用于描述工具用途。
- `FunctionTool.create(...)` 用于注册工具方法。
- 工具返回值通常需要是 JSON 可序列化结构。

示例代码：

```java
@Annotations.Schema(
        description = "当文档已经完成时，调用此函数退出循环"
)
public static Map<String, Object> exitLoop() {
    return Map.of();
}

LlmAgent refinerAgent = LlmAgent.builder()
        .name("RefinerAgent")
        .model(new SpringAI(chatModel))
        .instruction("""
                如果批评意见严格等于：
                No major issues found.

                你必须调用 exitLoop 函数。
                否则，请根据批评意见修改文档。
                """)
        .tools(FunctionTool.create(LoopAgentTest.class, "exitLoop"))
        .outputKey("current_document")
        .build();
```

## 12. InMemoryRunner 运行 Agent

涉及文件：

- `agent/SequentialAgentTest.java`
- `agent/ParallelAgentTest.java`
- `agent/LoopAgentTest.java`

知识点：

- `InMemoryRunner` 用于本地内存方式运行 Agent 应用。
- `Session` 表示一次用户会话。
- `runAsync(...)` 返回 `Flowable<Event>` 事件流。
- 通过 `event.finalResponse()` 判断最终响应。

示例代码：

```java
InMemoryRunner runner = new InMemoryRunner(agent, "app_name");

Session session = runner.sessionService()
        .createSession("app_name", "user_001")
        .blockingGet();

Content userMessage = Content.fromParts(
        Part.fromText("写一个 Java 阶乘函数")
);

Flowable<Event> eventStream = runner.runAsync(
        "user_001",
        session.id(),
        userMessage
);

eventStream.blockingForEach(event -> {
    if (event.finalResponse()) {
        System.out.println(event.stringifyContent());
    }
});
```

## 13. Prompt 约束输出格式

涉及文件：

- `agent/SequentialAgentTest.java`
- `agent/ParallelAgentTest.java`
- `agent/LoopAgentTest.java`

知识点：

- Prompt 中要明确角色、任务、输入、输出格式和限制。
- 对 Agent 流水线来说，稳定的输出格式非常重要。
- 循环流程中要明确退出条件。

代码生成 Prompt：

```java
.instruction("""
        You are a Java Code Generator.

        Based only on the user's request, write Java code.

        Output only the complete Java code block,
        enclosed in triple backticks.

        Do not add any explanation before or after the code.
        """)
```

代码审查 Prompt：

```java
.instruction("""
        You are an expert Java Code Reviewer.

        Please review the following code:

        ```java
        {generated_code}
        ```

        Review from:
        1. Correctness
        2. Readability
        3. Efficiency
        4. Edge cases
        5. Best practices

        If no major issues are found, output:
        No major issues found.

        Otherwise, output only concise review comments.
        """)
```

循环退出 Prompt：

```java
.instruction("""
        当前文档：
        {{current_document}}

        批评意见：
        {{criticism}}

        如果批评意见严格等于：
        No major issues found.

        你必须调用 exitLoop 函数。

        否则，请根据批评意见修改当前文档。
        只输出修改后的文档正文。
        """)
```

## 14. MCP SSE Transport

涉及文件：

- `tool/SpringAiToolTest.java`
- `tool/LangChain4jToolTest.java`
- `agent/SequentialAgentTest.java`

知识点：

- MCP SSE Transport 用于连接远程 MCP 服务。
- `baseUrl` 是 MCP 服务根地址。
- `sseEndpoint` 是 SSE 连接端点。
- 初始化后，客户端可向模型提供远程工具能力。

示例代码：

```java
public static McpSyncClient sseMcpClient() {
    HttpClientSseClientTransport transport =
            HttpClientSseClientTransport.builder("https://your-mcp-server/")
                    .sseEndpoint("sse")
                    .build();

    McpSyncClient client = McpClient.sync(transport)
            .requestTimeout(Duration.ofMinutes(5))
            .build();

    client.initialize();

    return client;
}
```

## 15. 安全注意事项

知识点：

- 不要把真实 `apiKey`、MCP key、访问令牌提交到 Git 仓库。
- 示例代码中的密钥应使用占位符。
- 实际项目建议使用环境变量、配置文件、CI/CD Secret 或配置中心。

示例代码：

```java
String apiKey = System.getenv("OPENAI_API_KEY");

OpenAiApi openAiApi = OpenAiApi.builder()
        .baseUrl("https://api.deepseek.com")
        .apiKey(apiKey)
        .completionsPath("v1/chat/completions")
        .embeddingsPath("v1/embeddings")
        .build();
```
