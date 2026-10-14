package com.zhiji.llm.domain;

/**
 * 限流与熔断配置（存 sys_config 的 llm.limits，2026-10 统一规划）：
 *
 * - C 端每账号一套日/周/月总量（不再按入口细分，自然周/自然月窗口）；
 * - B 端全站共享一套日/周/月总量（员工人工触发的总体额度）；
 * - siteDailyTotal 为全站兜底熔断（C+B 全部调用的账单红线，防异常刷量）。
 *
 * 三窗口均为硬限（超限抛 429，业务层按入口走降级路径）。
 */
public record LlmLimits(int customerDaily, int customerWeekly, int customerMonthly,
        int staffDaily, int staffWeekly, int staffMonthly, int siteDailyTotal) {

    public static LlmLimits defaults() {
        return new LlmLimits(200, 800, 2000, 300, 1200, 4000, 5000);
    }
}
