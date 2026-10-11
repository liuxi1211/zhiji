package com.zhiji.llm.service;

import com.zhiji.llm.config.LlmProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Spring AI 实现：用 OpenAI 兼容适配器对接各 provider（火山方舟 / DeepSeek / GLM 均兼容 OpenAI 协议）。
 * Chat 模型按 provider 缓存（baseUrl/apiKey 决定连接），模型名与采样参数按调用参数传入；
 * Embedding 模型按 provider+model 缓存（模型名在建实例时固定）。
 *
 * 只负责「把一次请求发出去」，不含路由、限额、计数、JSON 解析——那些在 LlmGatewayService。
 *
 * 超时必须显式给到 options：Spring AI 的 OpenAiChatOptions 在未指定时填 60s 默认值、maxRetries 填 3，
 * 且 2.0 的 OpenAiChatModel 在 prompt 自带 options 时不合并模型默认 options——不显式设置就是 60s × 多层重试，
 * 与核心模块定义 4.1 的「15s 超时一次重试后降级」相悖（重试统一由 LlmGatewayService 掌握，SDK 内层重试置 0）。
 * spec.timeout 为 null 时回落全局配置；思考模式经 extraBody 透传（OpenAI 兼容扩展字段）。
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
    public String call(LlmCallSpec spec, String systemPrompt, String userPrompt) {
        ChatModel chatModel = chatModels.computeIfAbsent(spec.provider(), this::buildChatModel);
        ChatResponse response = chatModel.call(new Prompt(messages(systemPrompt, userPrompt), optionsOf(spec)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    @Override
    public String stream(LlmCallSpec spec, String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        ChatModel chatModel = chatModels.computeIfAbsent(spec.provider(), this::buildChatModel);
        Flux<ChatResponse> flux = chatModel.stream(new Prompt(messages(systemPrompt, userPrompt), optionsOf(spec)));
        StringBuilder full = new StringBuilder();
        // block 住消费流（SSE 端点跑在虚拟线程上，阻塞无碍），逐 delta 回调给上层推给前端
        flux.doOnNext(response -> {
            Generation generation = response == null ? null : response.getResult();
            String delta = generation == null || generation.getOutput() == null ? null : generation.getOutput().getText();
            if (delta != null && !delta.isEmpty()) {
                full.append(delta);
                onDelta.accept(delta);
            }
        }).blockLast();
        return full.toString();
    }

    private List<Message> messages(String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));
        return messages;
    }

    /** 单次调用参数：模型名/采样参数/思考模式按 spec 传入，超时 spec 优先、回落全局。 */
    OpenAiChatOptions optionsOf(LlmCallSpec spec) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(spec.model())
                .timeout(spec.timeout() != null ? spec.timeout() : globalTimeout());
        if (spec.temperature() != null) {
            builder.temperature(spec.temperature());
        }
        if (spec.maxTokens() != null) {
            builder.maxTokens(spec.maxTokens());
        }
        Map<String, Object> extraBody = spec.extraBody();
        if (!extraBody.isEmpty()) {
            builder.extraBody(extraBody);
        }
        return builder.build();
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
                .timeout(globalTimeout())
                .maxRetries(0)
                .build();
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
                        .timeout(globalTimeout())
                        .maxRetries(0)
                        .build())
                .build();
    }

    private Duration globalTimeout() {
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
