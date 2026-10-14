package com.zhiji.llm.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmUsageService.WindowCounts;
import org.springframework.stereotype.Service;

/**
 * 限流与熔断守卫（成本治理 2026-10 统一规划），按调用侧别分策略：
 *
 * C 端（用户触发）：按账号日/周/月总量硬限（自然窗口），超限抛 SYS_RATE_LIMITED，
 * 由业务层走各入口的降级路径（判定降级自评等）。
 * B 端（员工人工触发）：全站共享一个计数，日/周/月总量硬限，超限提示调整配置。
 * 全站日熔断（siteDailyTotal）：C+B 全部调用的账单红线，对两端生效。
 *
 * 计数主体：C 端 = 账号（档案 id 在 LlmUsageService 统一映射），B 端 = 0 哨兵。
 * 采用「调用前检查」：SQLite 单机、窗口为区间聚合查询，毫秒级，无需缓存。
 */
@Service
public class LlmRateGuard {

    private final LlmConfigService configService;
    private final LlmUsageService usageService;

    public LlmRateGuard(LlmConfigService configService, LlmUsageService usageService) {
        this.configService = configService;
        this.usageService = usageService;
    }

    public void check(LlmPurpose purpose, long profileKey) {
        LlmLimits limits = configService.limits();

        // 全站日熔断：C/B 两端统一兜底（账单红线不区分谁触发）
        if (usageService.todayTotal() >= limits.siteDailyTotal()) {
            throw BizException.of(429, "SYS_CIRCUIT_OPEN", "今日全站调用量已达上限，请明日再试");
        }

        if (purpose.side() == LlmPurpose.Side.B) {
            WindowCounts counts = usageService.staffWindowCounts();
            requireWithin(counts, limits.staffDaily(), limits.staffWeekly(), limits.staffMonthly(),
                    "B 端", "请联系管理员调整限额配置");
            return;
        }

        long accountId = usageService.subjectKey(purpose, profileKey);
        if (accountId == 0L) {
            // 无账号上下文的 C 端调用（罕见）：仅受全站熔断约束
            return;
        }
        WindowCounts counts = usageService.windowCounts(accountId, LlmPurpose.Side.C);
        requireWithin(counts, limits.customerDaily(), limits.customerWeekly(), limits.customerMonthly(),
                "", "可先自评或明日再试");
    }

    /** 三窗口硬限：任一窗口超限即 429，文案带窗口口径便于前端直接展示。 */
    private void requireWithin(WindowCounts counts, int daily, int weekly, int monthly, String label, String hint) {
        if (counts.daily() >= daily) {
            throw limited(label + "今日额度已用完，" + hint);
        }
        if (counts.weekly() >= weekly) {
            throw limited(label + "本周额度已用完，" + hint);
        }
        if (counts.monthly() >= monthly) {
            throw limited(label + "本月额度已用完，" + hint);
        }
    }

    private BizException limited(String message) {
        return BizException.of(429, "SYS_RATE_LIMITED", message);
    }
}
