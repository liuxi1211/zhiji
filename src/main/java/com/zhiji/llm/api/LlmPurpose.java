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
    // 素材库 AI 辅助（核心模块定义 5.1/6.4）：B 端人工触发，profileId=null 走 0 哨兵计量
    SOURCE_REWRITE("source_rewrite", "素材 AI 改写", LimitKey.NONE, Tier.QUALITY),
    SOURCE_SPLIT("source_split", "素材一键拆分", LimitKey.NONE, Tier.QUALITY),
    SOURCE_SUMMARY("source_summary", "素材概述生成", LimitKey.NONE, Tier.QUALITY),
    SOURCE_CLASSIFY("source_classify", "素材分类建议", LimitKey.NONE, Tier.QUALITY),
    SOURCE_INFER("source_infer", "素材反推出题", LimitKey.NONE, Tier.QUALITY),
    SOURCE_DIFFICULTY("source_difficulty", "难度建议", LimitKey.NONE, Tier.QUALITY),
    // 标准库（T4/5.2）：同位语料合并重写，B 端合并页人工触发
    KB_MERGE("kb_merge", "同位语料合并", LimitKey.NONE, Tier.QUALITY);

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
