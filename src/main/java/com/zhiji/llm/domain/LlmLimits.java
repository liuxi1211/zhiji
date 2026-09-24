package com.zhiji.llm.domain;

/**
 * 限额与熔断配置（存 sys_config 的 llm.limits），口径见核心模块定义 4.2（D32 修订）。
 *
 * D32 限额分家：成本控制只落在单用户维度——judgeDaily 软上限、variantRewriteDaily 与
 * perProfileDailyTotal 硬熔断；siteCircuitEnabled=false（默认）时全站熔断不生效，
 * 开启后作为可选护栏仅统计 C 端消费类（profile_id != 0）达到 siteDailyTotal 时熔断，
 * B 端生产类（profile_id=0）不设限额、不计任何熔断。
 */
public record LlmLimits(int judgeDaily, int variantRewriteDaily, int perProfileDailyTotal,
        int siteDailyTotal, boolean siteCircuitEnabled) {

    public static LlmLimits defaults() {
        return new LlmLimits(200, 20, 300, 1000, false);
    }
}
