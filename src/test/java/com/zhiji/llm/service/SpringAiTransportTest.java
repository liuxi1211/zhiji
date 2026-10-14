package com.zhiji.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiji.common.config.ConfigRegistry;
import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmChannelConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 渠道参数装配（不触网）：超时与重试次数必须显式落到 options。
 * Spring AI 默认 60s + 3 次重试，漏设就会推翻核心模块定义 4.1 的降级口径。
 */
class SpringAiTransportTest {

    private SpringAiTransport transport;

    @BeforeEach
    void setUp() {
        LlmProperties properties = new LlmProperties();
        properties.setTimeoutSeconds(15);
        ConfigRegistry registry = Mockito.mock(ConfigRegistry.class);
        LlmChannelConfig.Channel ark = new LlmChannelConfig.Channel("openai",
                "https://ark.cn-beijing.volces.com/api/coding/v3", "test-key", List.of(), "deepseek-v4.1-flash");
        Mockito.when(registry.get("llm.channel"))
                .thenReturn(new LlmChannelConfig(Map.of("ark", ark)));
        transport = new SpringAiTransport(new LlmChannelService(registry, properties), properties);
    }

    @Test
    void providerOptionsCarryBaseUrlKeyTimeoutAndNoSdkRetry() {
        OpenAiChatOptions options = transport.providerOptions("ark");
        assertThat(options.getBaseUrl()).isEqualTo("https://ark.cn-beijing.volces.com/api/coding/v3");
        assertThat(options.getApiKey()).isEqualTo("test-key");
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(options.getMaxRetries()).isZero();
    }

    @Test
    void callOptionsCarryPerRequestTimeout() {
        OpenAiChatOptions options = transport.optionsOf(
                LlmCallSpec.of("ark", "doubao-seed-2-0-lite-260428", 0.0, 2048, null, null));
        assertThat(options.getModel()).isEqualTo("doubao-seed-2-0-lite-260428");
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getMaxTokens()).isEqualTo(2048);
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void callOptionsLeaveSamplingDefaultsWhenNotGiven() {
        OpenAiChatOptions options = transport.optionsOf(
                LlmCallSpec.of("ark", "doubao-seed-2-1-turbo-260628", null, null, null, null));
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getMaxTokens()).isNull();
    }

    @Test
    void callOptionsCarryRequestLevelTimeoutOverride() {
        OpenAiChatOptions options = transport.optionsOf(
                LlmCallSpec.of("ark", "doubao-seed-2-0-lite-260428", null, null, null, 180));
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(180));
    }

    @Test
    void callOptionsCarryThinkingExtraBody() {
        OpenAiChatOptions enabled = transport.optionsOf(
                LlmCallSpec.of("ark", "doubao-seed-2-0-lite-260428", null, null, true, null));
        assertThat(enabled.getExtraBody()).containsEntry("thinking", Map.of("type", "enabled"));
        OpenAiChatOptions disabled = transport.optionsOf(
                LlmCallSpec.of("ark", "doubao-seed-2-0-lite-260428", null, null, false, null));
        assertThat(disabled.getExtraBody()).containsEntry("thinking", Map.of("type", "disabled"));
        OpenAiChatOptions untouched = transport.optionsOf(
                LlmCallSpec.of("ark", "doubao-seed-2-0-lite-260428", null, null, null, null));
        assertThat(untouched.getExtraBody()).isNull();
    }

    @Test
    void unknownProviderFailsFast() {
        assertThatThrownBy(() -> transport.providerOptions("glm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("glm");
    }
}