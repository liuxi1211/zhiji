package com.zhiji.llm.api;

/**
 * 一次 LLM 调用请求。profileId 用于按档案计量限额与计数，无档案归属（B 端人工触发）传 null，
 * 由 llm 内部折算为 0 哨兵。temperature / maxTokens 为空时取路由配置值。
 */
public record LlmRequest(
        LlmPurpose purpose,
        Long profileId,
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Integer maxTokens) {

    public static LlmRequest of(LlmPurpose purpose, Long profileId, String systemPrompt, String userPrompt) {
        return new LlmRequest(purpose, profileId, systemPrompt, userPrompt, null, null);
    }

    public static LlmRequest user(LlmPurpose purpose, Long profileId, String userPrompt) {
        return new LlmRequest(purpose, profileId, null, userPrompt, null, null);
    }

    /** 计数与限额用的档案键：无档案统一为 0（与 llm_usage_daily.profile_id 约定一致）。 */
    public long profileKey() {
        return profileId == null ? 0L : profileId;
    }
}
