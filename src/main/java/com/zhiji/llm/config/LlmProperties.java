package com.zhiji.llm.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 连接配置（zhiji.llm）。2026-10 起 providers 部分降级为「首次启动种子」：
 * LlmChannelSection 用它把渠道（base-url/api-key/模型清单/默认模型）种入 sys_config 的 llm.channel，
 * 之后运行时事实源是渠道库（LLM 管理页可改，保存即生效）——改 yml 不再影响已初始化的系统。
 * timeoutSeconds 仍为部署形态参数（yml/环境变量），不进库。
 *
 * Provider.models：该 provider 的 API key 实际支持的模型清单（随种子入渠道库）；
 * Provider.defaultModel：该 provider 的默认模型（种子路由与存量路由归一化的取值来源）。
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

        /** API 协议：openai（OpenAI 兼容 /chat/completions，默认）或 anthropic（/v1/messages）。 */
        private String protocol = "openai";

        private String baseUrl;

        private String apiKey;

        /** 该 provider 支持的模型清单（设置页模型下拉的数据源；defaultModel 应包含在内）。 */
        private List<String> models = new ArrayList<>();

        /** 该 provider 的默认模型（路由种子与存量路由归一化用）。 */
        private String defaultModel;

        public String getProtocol() {
            return protocol == null || protocol.isBlank() ? "openai" : protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

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
