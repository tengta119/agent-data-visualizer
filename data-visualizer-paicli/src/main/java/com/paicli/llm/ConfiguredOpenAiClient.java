package com.paicli.llm;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** OpenAI-compatible endpoint configured by the host rather than PaiCLI provider defaults. */
public final class ConfiguredOpenAiClient extends AbstractOpenAiCompatibleClient {
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final OkHttpClient client = SHARED_HTTP_CLIENT.newBuilder()
            .dispatcher(new Dispatcher())
            .build();

    public ConfiguredOpenAiClient(String apiUrl, String apiKey, String model) {
        this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        try {
            return super.chat(messages, tools, listener);
        } catch (IOException error) {
            // The provider may echo credentials in an error body. Keep the typed failure,
            // but do not expose that untrusted body through the host-facing exception chain.
            throw new IOException("LLM request failed");
        }
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected OkHttpClient httpClient() {
        return client;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "openai-compatible";
    }
}
