package com.zhiji.llm.service;

import com.zhiji.llm.config.LlmProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;

/**
 * Spring AI 实现：用 OpenAI 兼容适配器对接各 provider（火山方舟 / DeepSeek / GLM 均兼容 OpenAI 协议）。
 * Chat 模型按 provider 缓存（baseUrl/apiKey 决定连接），模型名与温度按调用参数传入；
 * Embedding 模型按 provider+model 缓存（模型名在建实例时固定）。
 *
 * 只负责「把一次请求发出去」，不含路由、限额、计数、JSON 解析——那些在 LlmGatewayService。
 *
 * 超时必须显式给到 options：Spring AI 的 OpenAiChatOptions 在未指定时填 60s 默认值、maxRetries 填 3，
 * 且 2.0 的 OpenAiChatModel 在 prompt 自带 options 时不合并模型默认 options——不显式设置就是 60s × 多层重试，
 * 与核心模块定义 4.1 的「15s 超时一次重试后降级」相悖（重试统一由 LlmGatewayService 掌握，SDK 内层重试置 0）。
 */
@Component
public class SpringAiTransport implements LlmTransport {

    private final LlmProperties properties;
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModels = new ConcurrentHashMap<>();

    public SpringAiTransport(LlmProperties properties) {
        this.properties = properties;
    }

    @Override
    public String call(String provider, String model, Double temperature, Integer maxTokens,
            String systemPrompt, String userPrompt) {
        ChatModel chatModel = chatModels.computeIfAbsent(provider, this::buildChatModel);
        List<Message> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));
        ChatResponse response = chatModel.call(new Prompt(messages, callOptions(model, temperature, maxTokens)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    @Override
    public List<float[]> embed(String provider, String model, List<String> texts) {
        EmbeddingModel embeddingModel = embeddingModels.computeIfAbsent(provider + "::" + model,
                key -> buildEmbeddingModel(provider, model));
        return embeddingModel.embed(texts);
    }

    /** provider 级连接参数：baseUrl/apiKey 决定 HTTP 客户端指向，超时与重试次数在此定死。 */
    OpenAiChatOptions providerOptions(String provider) {
        LlmProperties.Provider target = requireProvider(provider);
        return OpenAiChatOptions.builder()
                .baseUrl(target.getBaseUrl())
                .apiKey(target.getApiKey())
                .timeout(timeout())
                .maxRetries(0)
                .build();
    }

    /** 单次调用参数：模型名与采样参数按调用方传入，超时同样要带上（per-request 超时只认 prompt options）。 */
    OpenAiChatOptions callOptions(String model, Double temperature, Integer maxTokens) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model)
                .timeout(timeout());
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        return builder.build();
    }

    private ChatModel buildChatModel(String provider) {
        return OpenAiChatModel.builder()
                .options(providerOptions(provider))
                .build();
    }

    private EmbeddingModel buildEmbeddingModel(String provider, String model) {
        LlmProperties.Provider target = requireProvider(provider);
        return OpenAiEmbeddingModel.builder()
                .options(OpenAiEmbeddingOptions.builder()
                        .baseUrl(target.getBaseUrl())
                        .apiKey(target.getApiKey())
                        .model(model)
                        .timeout(timeout())
                        .maxRetries(0)
                        .build())
                .build();
    }

    private Duration timeout() {
        return Duration.ofSeconds(properties.getTimeoutSeconds());
    }

    private LlmProperties.Provider requireProvider(String provider) {
        LlmProperties.Provider target = properties.getProviders().get(provider);
        if (target == null) {
            throw new IllegalArgumentException("未配置的 LLM provider: " + provider);
        }
        return target;
    }
}