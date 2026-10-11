package com.zhiji.llm.api;

/**
 * 一次 LLM 调用请求（builder 风格，业务按需覆盖，不填的参数回落路由配置）。
 *
 * 归属与计量：side 由 purpose 决定（C 端按档案限流 / B 端不限流）；profileId 是 C 端档案计量键，
 * 由 llm 内部折算 0 哨兵；B 端人工触发传 null。
 *
 * 可覆盖项（全部可选，null = 回落路由配置 llm.route）：
 *   model            单次调用换模型（如判定入口换更强模型做疑难判定）；
 *   temperature      采样温度；
 *   maxTokens        输出上限；
 *   thinking         思考模式开关：true/false 显式指定，null 跟随路由配置；
 *   timeoutSeconds   请求级超时（长任务放宽），null 回落全局 zhiji.llm.timeout-seconds。
 */
public class LlmRequest {

    private final LlmPurpose purpose;
    private final Long profileId;
    private final String systemPrompt;
    private final String userPrompt;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final Boolean thinking;
    private final Integer timeoutSeconds;

    private LlmRequest(Builder builder) {
        this.purpose = builder.purpose;
        this.profileId = builder.profileId;
        this.systemPrompt = builder.systemPrompt;
        this.userPrompt = builder.userPrompt;
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.thinking = builder.thinking;
        this.timeoutSeconds = builder.timeoutSeconds;
    }

    public static Builder of(LlmPurpose purpose, Long profileId, String userPrompt) {
        return new Builder(purpose, profileId, userPrompt);
    }

    /** C 端快捷构造：无系统提示词。 */
    public static Builder user(LlmPurpose purpose, Long profileId, String userPrompt) {
        return of(purpose, profileId, userPrompt);
    }

    /** B 端快捷构造：无档案归属（不限流、计量走 0 哨兵）。 */
    public static Builder admin(LlmPurpose purpose, String userPrompt) {
        return of(purpose, null, userPrompt);
    }

    public LlmPurpose purpose() {
        return purpose;
    }

    public Long profileId() {
        return profileId;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String userPrompt() {
        return userPrompt;
    }

    public String model() {
        return model;
    }

    public Double temperature() {
        return temperature;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public Boolean thinking() {
        return thinking;
    }

    public Integer timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 计数与限额用的档案键：无档案统一为 0（与 llm_usage_daily.profile_id 约定一致）。 */
    public long profileKey() {
        return profileId == null ? 0L : profileId;
    }

    public static final class Builder {

        private final LlmPurpose purpose;
        private final Long profileId;
        private final String userPrompt;
        private String systemPrompt;
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Boolean thinking;
        private Integer timeoutSeconds;

        private Builder(LlmPurpose purpose, Long profileId, String userPrompt) {
            this.purpose = purpose;
            this.profileId = profileId;
            this.userPrompt = userPrompt;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** 单次调用换模型（覆盖路由配置的模型名）。 */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /** 思考模式开关（OpenAI 兼容 extra_body.thinking 透传）：null = 跟随路由配置。 */
        public Builder thinking(Boolean thinking) {
            this.thinking = thinking;
            return this;
        }

        /** 请求级超时（秒）：长任务放宽用；null = 全局 zhiji.llm.timeout-seconds。 */
        public Builder timeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(this);
        }
    }
}
