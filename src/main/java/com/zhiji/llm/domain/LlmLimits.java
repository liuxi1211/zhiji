package com.zhiji.llm.domain;

/**
 * 限额与熔断配置（存 sys_config 的 llm.limits），默认值见核心模块定义 4.2。
 *
 * judgeDaily 为软上限（超限不阻断，仅告警）；variantRewriteDaily、perProfileDailyTotal、
 * siteDailyTotal 为硬熔断（超限抛业务异常，由业务层降级）。
 */
public record LlmLimits(int judgeDaily, int variantRewriteDaily, int perProfileDailyTotal, int siteDailyTotal) {

    public static LlmLimits defaults() {
        return new LlmLimits(200, 20, 300, 1000);
    }
}
