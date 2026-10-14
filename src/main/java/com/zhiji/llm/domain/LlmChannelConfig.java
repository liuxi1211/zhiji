package com.zhiji.llm.domain;

import com.zhiji.llm.config.LlmProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型渠道配置（存 sys_config 的 llm.channel）：渠道名 → 连接信息与模型清单。
 * 运行时事实源（LLM 管理页可改，保存即生效）；application.yml 的 zhiji.llm.providers
 * 仅作首次启动种子（empty 库时导入，存量部署升级时自动补种）。
 * apiKey 随库存储，页面脱敏展示（掩形 ****abcd），传输层经 LlmChannelService 读取明文。
 */
public record LlmChannelConfig(Map<String, Channel> channels) {

    /** 密钥掩码前缀：掩码形如 ****abcd；merge 以该前缀识别「未修改」的密钥（真实密钥不可能以 * 开头）。 */
    public static final String MASK_PREFIX = "****";

    public record Channel(String protocol, String baseUrl, String apiKey,
            List<String> models, String defaultModel) {

        public Channel {
            models = models == null ? List.of() : models;
        }

        /** protocol 不写默认 openai（OpenAI 兼容 /chat/completions）；anthropic 为 messages 协议（无 embedding）。 */
        public String protocolOrDefault() {
            return protocol == null || protocol.isBlank() ? "openai" : protocol;
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
        }
    }

    public LlmChannelConfig {
        channels = channels == null ? Map.of() : channels;
    }

    public Channel channel(String name) {
        return channels.get(name);
    }

    /** 渠道密钥脱敏：保留末 4 位便于核对是哪把 key；未配置或过短统一返回空串。 */
    public static String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        return apiKey.length() <= 4 ? MASK_PREFIX : MASK_PREFIX + apiKey.substring(apiKey.length() - 4);
    }

    /** 展示副本：apiKey 替换为掩码，仅用于 GET 下发，不进快照、不落库。 */
    public LlmChannelConfig masked() {
        Map<String, Channel> copy = new LinkedHashMap<>();
        channels.forEach((name, channel) -> copy.put(name, new Channel(channel.protocol(), channel.baseUrl(),
                maskKey(channel.apiKey()), channel.models(), channel.defaultModel())));
        return new LlmChannelConfig(copy);
    }

    /** 首启种子 / 快照缺失兜底：yml providers → 渠道配置。 */
    public static LlmChannelConfig fromSeed(Map<String, LlmProperties.Provider> providers) {
        Map<String, Channel> channels = new LinkedHashMap<>();
        providers.forEach((name, provider) -> channels.put(name, new Channel(provider.getProtocol(),
                provider.getBaseUrl(), provider.getApiKey(), provider.getModels(), provider.getDefaultModel())));
        return new LlmChannelConfig(channels);
    }
}
