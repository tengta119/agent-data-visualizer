package top.lbwxxc.ai.domain.agent.service.paicli;

import com.paicli.llm.LlmClient;

@FunctionalInterface
public interface PaiCliModelFactory {
    LlmClient create(ModelSettings settings);

    record ModelSettings(String endpoint, String apiKey, String model) {
        @Override
        public String toString() {
            return "ModelSettings[endpoint=***, apiKey=***, model=" + model + "]";
        }
    }
}
