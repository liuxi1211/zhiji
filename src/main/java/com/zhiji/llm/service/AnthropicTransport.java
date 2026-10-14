package com.zhiji.llm.service;

import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmChannelConfig.Channel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic messages 协议实现（POST {base-url}/v1/messages），面向公司代理渠道（jlc）。
 * 与 SpringAiTransport 平级，按渠道的 protocol 配置由 LlmTransportRouter 分发。
 * 每次调用现读渠道配置（llm.channel 快照），渠道保存后无需任何缓存失效即生效。
 *
 * 协议要点（jlc 渠道实测）：
 * - 认证头 x-api-key + anthropic-version；
 * - system 放顶层字段而非 messages；
 * - 三家模型（deepseek/glm/doubao）默认开启思考，路由未显式给 thinking 时统一关闭，
 *   否则 maxTokens 全被 thinking 吃掉、正文为空（判定类 JSON 调用会全军覆没）；
 * - 响应 content 是块数组，取 type=text 的块拼接（thinking/signature 等块跳过）。
 */
public class AnthropicTransport implements LlmTransport {

    private final LlmChannelService channels;
    private final LlmProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final JsonMapper jsonMapper = new JsonMapper();

    public AnthropicTransport(LlmChannelService channels, LlmProperties properties) {
        this.channels = channels;
        this.properties = properties;
    }

    @Override
    public String call(LlmCallSpec spec, String systemPrompt, String userPrompt) {
        JsonNode response = send(spec, systemPrompt, userPrompt, false);
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asString())) {
                text.append(block.path("text").asString());
            }
        }
        return text.toString();
    }

    @Override
    public String stream(LlmCallSpec spec, String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        try {
            HttpRequest request = buildRequest(spec, systemPrompt, userPrompt, true);
            // SSE 逐行读（SSE 端点跑在虚拟线程上，阻塞无碍），只把 text_delta 推给上层
            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + "：" + readAll(response.body()));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        continue;
                    }
                    JsonNode event = jsonMapper.readTree(payload);
                    String type = event.path("type").asString();
                    if ("content_block_delta".equals(type)
                            && "text_delta".equals(event.path("delta").path("type").asString())) {
                        String delta = event.path("delta").path("text").asString();
                        if (!delta.isEmpty()) {
                            full.append(delta);
                            onDelta.accept(delta);
                        }
                    } else if ("error".equals(type)) {
                        throw new IllegalStateException("流式返回错误：" + event.path("error").path("message").asString());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("流式调用失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流式调用被中断", e);
        }
        return full.toString();
    }

    /** Anthropic 渠道无 embedding 端点（jlc 只代理 messages），向量化路由不可指向此类渠道。 */
    @Override
    public java.util.List<float[]> embed(String provider, String model, java.util.List<String> texts) {
        throw new UnsupportedOperationException(
                "anthropic 协议渠道不支持向量化：" + provider + "（embedding 路由请指向 openai 协议渠道）");
    }

    private JsonNode send(LlmCallSpec spec, String systemPrompt, String userPrompt, boolean stream) {
        try {
            HttpResponse<String> response = httpClient.send(buildRequest(spec, systemPrompt, userPrompt, stream),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + "：" + response.body());
            }
            return jsonMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("调用失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用被中断", e);
        }
    }

    private HttpRequest buildRequest(LlmCallSpec spec, String systemPrompt, String userPrompt, boolean stream) {
        Channel provider = channels.require(spec.provider());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", spec.model());
        // Anthropic 协议 max_tokens 必填（模型默认值无法省略表达）：路由不再管理输出预算，
        // 请求未覆盖时给协议兜底宽裕上限；思考模型的大输出请走请求级 .maxTokens() 覆盖
        body.put("max_tokens", spec.maxTokens() != null ? spec.maxTokens() : 8192);
        if (spec.temperature() != null) {
            body.put("temperature", spec.temperature());
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", java.util.List.of(Map.of("role", "user", "content", userPrompt)));
        // 三家模型默认开思考：显式关闭保证正文预算（请求级 thinking=true 时保留思考）
        Boolean thinking = spec.thinking();
        body.put("thinking", Map.of("type", Boolean.TRUE.equals(thinking) ? "enabled" : "disabled"));
        if (stream) {
            body.put("stream", true);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(provider.baseUrl() + "/v1/messages"))
                .timeout(timeoutOf(spec))
                .header("x-api-key", provider.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
    }

    private Duration timeoutOf(LlmCallSpec spec) {
        return spec.timeout() != null ? spec.timeout() : Duration.ofSeconds(properties.getTimeoutSeconds());
    }

    private String readAll(java.io.InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
