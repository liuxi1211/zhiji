package com.zhiji.llm.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 连接配置（zhiji.llm）。providers 的 base-url 与 api-key 只来自此处或环境变量，
 * 不进数据库、不进设置页——安全边界（决策 T2-D3）。
 *
 * Provider.models：该 provider 的 API key 实际支持的模型清单（部署形态事实，随 yml 维护）；
 * Provider.defaultModel：该 provider 的默认模型（种子路由与存量路由归一化的取值来源）。
 * 换 provider 或模型上线/下线只改 yml，不改代码、不动 sys_config。
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

        /** 该 provider 支持的模型清单（设置页模型下拉的数据源；defaultModel 应包含在内）。 */
        private List<String> models = new ArrayList<>();

        /** 该 provider 的默认模型（路由种子与存量路由归一化用）。 */
        private String defaultModel;

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

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
        }
    }
}
