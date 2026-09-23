package com.zhiji.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiji.llm.config.LlmProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * provider 参数装配（不触网）：超时与重试次数必须显式落到 options。
 * Spring AI 默认 60s + 3 次重试，漏设就会推翻核心模块定义 4.1 的降级口径。
 */
class SpringAiTransportTest {

    private SpringAiTransport transport;

    @BeforeEach
    void setUp() {
        LlmProperties properties = new LlmProperties();
        properties.setTimeoutSeconds(15);
        LlmProperties.Provider ark = new LlmProperties.Provider();
        ark.setBaseUrl("https://ark.cn-beijing.volces.com/api/coding/v3");
        ark.setApiKey("test-key");
        Map<String, LlmProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("ark", ark);
        properties.setProviders(providers);
        transport = new SpringAiTransport(properties);
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
        OpenAiChatOptions options = transport.callOptions("doubao-seed-2-0-lite-260428", 0.0, 2048);
        assertThat(options.getModel()).isEqualTo("doubao-seed-2-0-lite-260428");
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getMaxTokens()).isEqualTo(2048);
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void callOptionsLeaveSamplingDefaultsWhenNotGiven() {
        OpenAiChatOptions options = transport.callOptions("doubao-seed-2-1-turbo-260628", null, null);
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getMaxTokens()).isNull();
    }

    @Test
    void unknownProviderFailsFast() {
        assertThatThrownBy(() -> transport.providerOptions("glm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("glm");
    }
}