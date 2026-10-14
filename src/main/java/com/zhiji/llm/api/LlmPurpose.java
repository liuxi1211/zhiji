package com.zhiji.llm.api;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用入口（用途）。业务模块按用途发起调用，不认具体模型名——
 * 模型路由以 purpose 为键，限流按侧别（C 端每账号 / B 端全站）总量控制，
 * 不再按入口细分（成本治理 2026-10 统一规划：限流到总量，统计保留入口细分）。
 *
 * side：调用侧别——C 端（用户触发，按账号限额）/ B 端（员工人工触发，全站总额度）。
 * tier：默认模型档，仅作为路由种子分档（路由仍以 sys_config 为准）。
 */
@ConstantGroup("llm_purpose")
public enum LlmPurpose implements ConstantItem {

    // —— C 端（用户触发：按账号日/周/月限额 + 全站日熔断） ——
    JUDGE_PRACTICE("judge_practice", "练习模式判定", Side.C, Tier.JUDGE),
    // 显示名按 6.5 命名表去游戏化（"抽卡"退役为"随机复习"）；code 不动以保既有 sys_config 路由数据
    JUDGE_GACHA("judge_gacha", "随机复习判定", Side.C, Tier.JUDGE),
    JUDGE_EXAM("judge_exam", "模拟考逐问判定", Side.C, Tier.JUDGE),
    PROFILE_DRAFT("profile_draft", "建档整理", Side.C, Tier.QUALITY),
    NARRATIVE_DRAFT("narrative_draft", "叙事故事起草", Side.C, Tier.QUALITY),
    EXAM_SUMMARY("exam_summary", "教练总评", Side.C, Tier.QUALITY),
    VARIANT_REWRITE("variant_rewrite", "变体现场改写", Side.C, Tier.QUALITY),
    SPLIT_FALLBACK("split_fallback", "拆题兜底", Side.C, Tier.QUALITY),
    CLUSTER_DEDUP("cluster_dedup", "聚类去重", Side.C, Tier.QUALITY),
    AI_COMPOSE("ai_compose", "AI 成稿", Side.C, Tier.QUALITY),
    EMBEDDING("embedding", "向量化", Side.C, Tier.EMBED),

    // —— B 端（员工人工触发：全站日/周/月总额度 + 全站日熔断） ——
    SOURCE_REWRITE("source_rewrite", "素材 AI 改写", Side.B, Tier.QUALITY),
    SOURCE_TITLE("source_title", "标题生成", Side.B, Tier.QUALITY),
    SOURCE_FOLLOWUP("source_followup", "追问链识别", Side.B, Tier.QUALITY),
    SOURCE_SUMMARY("source_summary", "素材概述生成", Side.B, Tier.QUALITY),
    SOURCE_DOMAIN("source_locate", "领域主题定位", Side.B, Tier.QUALITY),
    SOURCE_CLASSIFY("source_classify", "素材分类建议", Side.B, Tier.QUALITY),
    SOURCE_INFER("source_infer", "素材反推出题", Side.B, Tier.QUALITY),
    SOURCE_DIFFICULTY("source_difficulty", "难度建议", Side.B, Tier.QUALITY),
    KB_MERGE("kb_merge", "同位语料合并", Side.B, Tier.QUALITY);

    /** 调用侧别：决定限流策略——C 端按账号日/周/月限额，B 端全站总额度。 */
    public enum Side {
        C, B
    }

    /** 默认模型档：判定用便宜模型、内容生成用好模型、向量化走 embedding（路由种子分档用）。 */
    public enum Tier {
        JUDGE, QUALITY, EMBED
    }

    private final String code;
    private final String name;
    private final Side side;
    private final Tier tier;

    LlmPurpose(String code, String name, Side side, Tier tier) {
        this.code = code;
        this.name = name;
        this.side = side;
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

    public Tier tier() {
        return tier;
    }

    /** 指定侧别的入口 code 列表（限流/统计按侧别过滤计数行用）。 */
    public static List<String> codes(Side side) {
        List<String> codes = new ArrayList<>();
        for (LlmPurpose purpose : values()) {
            if (purpose.side == side) {
                codes.add(purpose.code);
            }
        }
        return codes;
    }

    public static LlmPurpose fromCode(String code) {
        for (LlmPurpose purpose : values()) {
            if (purpose.code.equals(code)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("未知 LLM 入口: " + code);
    }

    /** code 是否为当前版本定义的入口（存量库自愈用：afterLoad 剔除已下线入口的残留路由）。 */
    public static boolean isValid(String code) {
        for (LlmPurpose purpose : values()) {
            if (purpose.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
