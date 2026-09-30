package top.lbwxxc.ai.test.paicli;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowResult;
import top.lbwxxc.ai.domain.agent.service.paicli.PaiCliWorkflowRuntime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaiCliDefaultConfigurationTest {
    @Test
    void bindsAndExecutesTheDefaultYamlWithoutARealModel() throws IOException {
        String yaml = new ClassPathResource("agent/data-visualizer-agent.yml")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("${open-ai.key}", "test-key");
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        Properties properties = factory.getObject();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> value : properties.entrySet()) {
            values.put(String.valueOf(value.getKey()), value.getValue());
        }
        AiAgentAutoConfigProperties config = new Binder(new MapConfigurationPropertySource(values))
                .bind("ai.agent.config", Bindable.of(AiAgentAutoConfigProperties.class))
                .orElseThrow(IllegalArgumentException::new);
        AtomicReference<String> endpoint = new AtomicReference<>();
        PaiCliWorkflowRuntime runtime = new PaiCliWorkflowRuntime(settings -> {
            endpoint.set(settings.endpoint());
            return new FakeClient();
        }, (agent, tools) -> assertEquals(1, tools.size()));

        runtime.install(config);
        String sessionId = runtime.createSession("100003", "test-user");
        PaiCliWorkflowResult result = runtime.run("100003", "test-user", sessionId, "画一个流程图");

        assertEquals("{\"type\":\"user\",\"content\":\"补充信息\"}", result.content());
        assertEquals(List.of("analysis_result", "draft_diagram", "final_result"),
                List.copyOf(result.outputs().keySet()));
        assertEquals("https://api.deepseek.com/v1/chat/completions", endpoint.get());
    }

    private static final class FakeClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return answer();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return answer();
        }

        private ChatResponse answer() {
            return new ChatResponse("assistant", "{\"type\":\"user\",\"content\":\"补充信息\"}",
                    List.of(), 1, 1);
        }

        @Override
        public String getModelName() {
            return "fake";
        }

        @Override
        public String getProviderName() {
            return "fake";
        }
    }
}
