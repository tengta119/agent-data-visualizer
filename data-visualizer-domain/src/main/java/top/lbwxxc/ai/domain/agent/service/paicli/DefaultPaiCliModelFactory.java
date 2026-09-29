package top.lbwxxc.ai.domain.agent.service.paicli;

import com.paicli.llm.ConfiguredOpenAiClient;
import com.paicli.llm.LlmClient;
import org.springframework.stereotype.Component;

@Component
public final class DefaultPaiCliModelFactory implements PaiCliModelFactory {
    @Override
    public LlmClient create(ModelSettings settings) {
        return new ConfiguredOpenAiClient(settings.endpoint(), settings.apiKey(), settings.model());
    }
}
