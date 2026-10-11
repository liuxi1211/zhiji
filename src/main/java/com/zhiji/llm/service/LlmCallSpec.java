package com.zhiji.llm.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次网络调用的全部参数（路由解析与请求级覆盖合并后的结果，由 Gateway 组装）。
 * Transport 只照单发请求，不再感知「哪个参数来自路由、哪个来自调用方覆盖」。
 *
 * thinking 为 Boolean 三态：null 不传（跟随 provider 默认）；true/false 经 OpenAI 兼容
 * extra_body.thinking 透传（火山方舟 doubao-seed / DeepSeek 通用格式）。
 */
public record LlmCallSpec(
        String provider,
        String model,
        Double temperature,
        Integer maxTokens,
        Boolean thinking,
        Duration timeout) {

    public static LlmCallSpec of(String provider, String model, Double temperature, Integer maxTokens,
            Boolean thinking, Integer timeoutSeconds) {
        return new LlmCallSpec(provider, model, temperature, maxTokens, thinking,
                timeoutSeconds == null || timeoutSeconds <= 0 ? null : Duration.ofSeconds(timeoutSeconds));
    }

    /** 思考模式 → OpenAI 兼容 extra_body（provider 无关；不支持的 provider 会忽略未知扩展字段）。 */
    public Map<String, Object> extraBody() {
        if (thinking == null) {
            return Map.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        return body;
    }
}
