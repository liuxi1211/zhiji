package com.zhiji.llm.api;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * LLM 调用入口（用途）。业务模块按用途发起调用，不认具体模型名——
 * 模型路由与限额均以 purpose 为键（核心模块定义 4.2 成本治理三层入口）。
 *
 * side：调用侧别——C 端（用户触发，按档案限流）/ B 端（内容维护者人工触发，不限流）。
 * limitKey：C 端入口受哪档限额约束；B 端入口一律 NONE（限额检查直接跳过）。
 * tier：默认模型档，仅作为路由未配置时的兜底提示（路由仍以 sys_config 为准）。
 */
@ConstantGroup("llm_purpose")
public enum LlmPurpose implements ConstantItem {

    // —— C 端（用户触发：按档案限额 + 全站熔断） ——
    JUDGE_PRACTICE("judge_practice", "练习模式判定", Side.C, LimitKey.JUDGE, Tier.JUDGE),
    // 显示名按 6.5 命名表去游戏化（"抽卡"退役为"随机复习"）；code 不动以保既有 sys_config 路由数据
    JUDGE_GACHA("judge_gacha", "随机复习判定", Side.C, LimitKey.JUDGE, Tier.JUDGE),
    JUDGE_EXAM("judge_exam", "模拟考逐问判定", Side.C, LimitKey.NONE, Tier.JUDGE),
    PROFILE_DRAFT("profile_draft", "建档整理", Side.C, LimitKey.NONE, Tier.QUALITY),
    NARRATIVE_DRAFT("narrative_draft", "叙事故事起草", Side.C, LimitKey.NONE, Tier.QUALITY),
    EXAM_SUMMARY("exam_summary", "教练总评", Side.C, LimitKey.NONE, Tier.QUALITY),
    VARIANT_REWRITE("variant_rewrite", "变体现场改写", Side.C, LimitKey.VARIANT, Tier.QUALITY),
    SPLIT_FALLBACK("split_fallback", "拆题兜底", Side.C, LimitKey.NONE, Tier.QUALITY),
    CLUSTER_DEDUP("cluster_dedup", "聚类去重", Side.C, LimitKey.NONE, Tier.QUALITY),
    AI_COMPOSE("ai_compose", "AI 成稿", Side.C, LimitKey.NONE, Tier.QUALITY),
    EMBEDDING("embedding", "向量化", Side.C, LimitKey.NONE, Tier.EMBED),

    // —— B 端（内容维护者人工触发：不限流，仅全站熔断兜底 + 计量可观测） ——
    SOURCE_REWRITE("source_rewrite", "素材 AI 改写", Side.B, LimitKey.NONE, Tier.QUALITY),
    SOURCE_TITLE("source_title", "标题生成", Side.B, LimitKey.NONE, Tier.QUALITY),
    SOURCE_FOLLOWUP("source_followup", "追问链识别", Side.B, LimitKey.NONE, Tier.QUALITY),
    SOURCE_SUMMARY("source_summary", "素材概述生成", Side.B, LimitKey.NONE, Tier.QUALITY),
    SOURCE_CLASSIFY("source_classify", "素材分类建议", Side.B, LimitKey.NONE, Tier.QUALITY),
    SOURCE_INFER("source_infer", "素材反推出题", Side.B, LimitKey.NONE, Tier.QUALITY),
    SOURCE_DIFFICULTY("source_difficulty", "难度建议", Side.B, LimitKey.NONE, Tier.QUALITY),
    KB_MERGE("kb_merge", "同位语料合并", Side.B, LimitKey.NONE, Tier.QUALITY);

    /** 调用侧别：决定限额守卫策略——C 端按档案限流，B 端跳过档案级限额。 */
    public enum Side {
        /** C 端用户触发：受档案级限额约束（judgeDaily / variantRewriteDaily / perProfileDailyTotal）。 */
        C,
        /** B 端员工人工触发：不受档案级限额，仅全站熔断兜底；计数照记（用量可观测）。 */
        B
    }

    /** C 端限额档：判定软上限 / 变体硬上限 / 无独立限额（仅档案合计与全站熔断兜底）。B 端恒为 NONE。 */
    public enum LimitKey {
        JUDGE, VARIANT, NONE
    }

    /** 默认模型档：判定用便宜模型温度 0、内容生成用好模型、向量化走 embedding。 */
    public enum Tier {
        JUDGE, QUALITY, EMBED
    }

    private final String code;
    private final String name;
    private final Side side;
    private final LimitKey limitKey;
    private final Tier tier;

    LlmPurpose(String code, String name, Side side, LimitKey limitKey, Tier tier) {
        this.code = code;
        this.name = name;
        this.side = side;
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

    public Side side() {
        return side;
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
