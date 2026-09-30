package top.lbwxxc.ai.config;

import top.lbwxxc.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import top.lbwxxc.ai.domain.agent.service.paicli.IPaiCliWorkflowService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = Logger.getLogger(AiAgentAutoConfig.class.getName());

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IPaiCliWorkflowService workflowService;

    @Resource
    private Environment environment;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            log.info("Ai Agent 智能体装配 {}");

            String rawYaml = readCurrentRawYaml();

            if (rawYaml == null) {
                log.info("使用默认配置装配 Agent");
                workflowService.install(aiAgentAutoConfigProperties);
            } else {
                log.info("使用本地配置装配");
                AiAgentAutoConfigProperties currentAiAgentAutoConfigProperties = parseYaml(rawYaml);
                workflowService.install(currentAiAgentAutoConfigProperties);
            }

        } catch (Exception e) {
            log.log(Level.SEVERE, "Ai Agent 自动装配失败，应用继续启动，请检查外部 MCP/Agent 配置", e);
        }
    }

    private String readCurrentRawYaml() throws IOException {
        Path configPath = Paths.get("config", "data-visualizer-agent.yml").toAbsolutePath().normalize();
        if (!Files.exists(configPath)) {
            return null;
        }

        return Files.readString(configPath, StandardCharsets.UTF_8);
    }

    private AiAgentAutoConfigProperties parseYaml(String rawYaml) {
        String resolvedYaml = environment.resolvePlaceholders(rawYaml);
        YamlPropertiesFactoryBean yamlPropertiesFactoryBean = new YamlPropertiesFactoryBean();
        yamlPropertiesFactoryBean.setResources(new ByteArrayResource(resolvedYaml.getBytes(StandardCharsets.UTF_8)));

        Properties properties = yamlPropertiesFactoryBean.getObject();
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("agent 配置解析失败，未读取到有效内容");
        }

        Map<String, Object> source = new LinkedHashMap<>();
        // 不能使用 stringPropertyNames()，否则像 agent-id: 100003 这种被解析成数字的值会被过滤掉。
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            source.put(name, value);
        }

        AiAgentAutoConfigProperties config = new Binder(new MapConfigurationPropertySource(source))
                .bind("ai.agent.config", Bindable.of(AiAgentAutoConfigProperties.class))
                .orElseThrow(() -> new IllegalArgumentException("agent 配置绑定失败，请检查 ai.agent.config 结构"));

        if (config.getTables() == null || config.getTables().isEmpty()) {
            throw new IllegalArgumentException("agent 配置 tables 不能为空");
        }

        return config;
    }

}
