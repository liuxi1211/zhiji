package com.zhiji.llm.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 连接配置（zhiji.llm）。providers 的 base-url 与 api-key 只来自此处或环境变量，
 * 不进数据库、不进设置页——安全边界（决策 T2-D3）。
 */
@ConfigurationProperties(prefix = "zhiji.llm")
public class LlmProperties {

    private int timeoutSeconds = 15;

    private Map<String, Provider> providers = new LinkedHashMap<>();

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    public static class Provider {

        private String baseUrl;

        private String apiKey;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
        }
    }
}
