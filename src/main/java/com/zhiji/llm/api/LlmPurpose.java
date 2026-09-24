package com.zhiji.llm.api;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * LLM 调用入口（用途）。业务模块按用途发起调用，不认具体模型名——
 * 模型路由与限额均以 purpose 为键（核心模块定义 4.2 成本治理三层入口）。
 *
 * limitKey：该入口受哪档限额约束；none 表示只受单档案合计与全站熔断兜底。
 * tier：默认模型档，仅作为路由未配置时的兜底提示（路由仍以 sys_config 为准）。
 */
@ConstantGroup("llm_purpose")
public enum LlmPurpose implements ConstantItem {

    JUDGE_PRACTICE("judge_practice", "练习模式判定", LimitKey.JUDGE, Tier.JUDGE),
    JUDGE_GACHA("judge_gacha", "抽卡判定", LimitKey.JUDGE, Tier.JUDGE),
    JUDGE_EXAM("judge_exam", "模拟考逐问判定", LimitKey.NONE, Tier.JUDGE),
    PROFILE_DRAFT("profile_draft", "建档整理", LimitKey.NONE, Tier.QUALITY),
    NARRATIVE_DRAFT("narrative_draft", "叙事故事起草", LimitKey.NONE, Tier.QUALITY),
    EXAM_SUMMARY("exam_summary", "教练总评", LimitKey.NONE, Tier.QUALITY),
    VARIANT_REWRITE("variant_rewrite", "变体现场改写", LimitKey.VARIANT, Tier.QUALITY),
    SPLIT_FALLBACK("split_fallback", "拆题兜底", LimitKey.NONE, Tier.QUALITY),
    CLUSTER_DEDUP("cluster_dedup", "聚类去重", LimitKey.NONE, Tier.QUALITY),
    AI_COMPOSE("ai_compose", "AI 成稿", LimitKey.NONE, Tier.QUALITY),
    EMBEDDING("embedding", "向量化", LimitKey.NONE, Tier.EMBED),
    DOC_FORM_DETECT("doc_form_detect", "素材形态识别", LimitKey.NONE, Tier.QUALITY),
    QUESTION_GEN("question_gen", "资料反推出题", LimitKey.NONE, Tier.QUALITY),
    INTERVIEW_EXTRACT("interview_extract", "面经抽题", LimitKey.NONE, Tier.QUALITY);

    /** 限额档：判定软上限 / 变体硬上限 / 无独立限额（仅全局兜底）。 */
    public enum LimitKey {
        JUDGE, VARIANT, NONE
    }

    /** 默认模型档：判定用便宜模型温度 0、内容生成用好模型、向量化走 embedding。 */
    public enum Tier {
        JUDGE, QUALITY, EMBED
    }

    private final String code;
    private final String name;
    private final LimitKey limitKey;
    private final Tier tier;

    LlmPurpose(String code, String name, LimitKey limitKey, Tier tier) {
        this.code = code;
        this.name = name;
        this.limitKey = limitKey;
        this.tier = tier;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    public LimitKey limitKey() {
        return limitKey;
    }

    public Tier tier() {
        return tier;
    }

    public static LlmPurpose fromCode(String code) {
        for (LlmPurpose purpose : values()) {
            if (purpose.code.equals(code)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("未知 LLM 入口: " + code);
    }
}
