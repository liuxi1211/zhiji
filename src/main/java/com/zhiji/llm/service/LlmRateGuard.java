package com.zhiji.llm.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import org.springframework.stereotype.Service;

/**
 * 限额与熔断守卫（核心模块定义 4.2 成本治理）。
 *
 * 软上限（判定类 judgeDaily）：不阻断调用，由用量视图标记告警；
 * 硬熔断（变体改写 / 单档案当日合计 / 全站当日合计）：超限抛业务异常，由业务层降级。
 * 采用「调用前检查」而非事前预留：SQLite 单写锁下并发压力小，日粒度计数足够。
 */
@Service
public class LlmRateGuard {

    private final LlmConfigService configService;
    private final LlmUsageService usageService;

    public LlmRateGuard(LlmConfigService configService, LlmUsageService usageService) {
        this.configService = configService;
        this.usageService = usageService;
    }

    public void check(LlmPurpose purpose, long profileId) {
        LlmLimits limits = configService.limits();

        if (usageService.todayTotal() >= limits.siteDailyTotal()) {
            throw BizException.of(429, "SYS_CIRCUIT_OPEN", "今日服务调用量已达上限，请明日再试");
        }

        if (profileId != 0L && usageService.todayTotalByProfile(profileId) >= limits.perProfileDailyTotal()) {
            throw BizException.of(429, "SYS_RATE_LIMITED", "今日调用次数已达上限，可先自评");
        }

        if (purpose.limitKey() == LlmPurpose.LimitKey.VARIANT
                && usageService.todayCallCount(purpose.getCode(), profileId) >= limits.variantRewriteDaily()) {
            throw BizException.of(429, "SYS_RATE_LIMITED", "今日变体改写次数已达上限");
        }

        // judgeDaily 为软上限：超限不阻断，仅在用量视图标记告警（核心模块定义 4.2）。
    }
}
