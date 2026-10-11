package com.zhiji.llm.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import org.springframework.stereotype.Service;

/**
 * 限额与熔断守卫（核心模块定义 4.2 成本治理），按调用侧别分策略：
 *
 * C 端（用户触发）：档案级限额全量生效——判定软上限（告警不阻断）、变体硬上限、
 * 单档案当日合计硬熔断；全站熔断对两端生效。
 * B 端（员工人工触发）：档案级限额一律跳过（内容维护者的 AI 改写/拆分不该被用户额度误伤），
 * 仅保留全站每日熔断兜底与用量可观测。
 *
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

        // 全站熔断：C/B 两端统一兜底（总成本红线不区分谁触发的）
        if (usageService.todayTotal() >= limits.siteDailyTotal()) {
            throw BizException.of(429, "SYS_CIRCUIT_OPEN", "今日服务调用量已达上限，请明日再试");
        }

        // B 端人工触发：不受档案级限额（人工单次操作不构成滥用向量），到此为止
        if (purpose.side() == LlmPurpose.Side.B) {
            return;
        }

        if (profileId != 0L && usageService.todayTotalByProfile(profileId) >= limits.perProfileDailyTotal()) {
            throw BizException.of(429, "SYS_RATE_LIMITED", "今日调用次数已达上限，可先自评");
        }

        if (purpose.limitKey() == LlmPurpose.LimitKey.VARIANT
                && usageService.todayCallCount(purpose.getCode(), profileId) >= limits.variantRewriteDaily()) {
            throw BizException.of(429, "SYS_RATE_LIMITED", "今日变体改写次数已达上限");
        }

        // judgeDaily 为 C 端软上限：超限不阻断，仅在用量视图标记告警（核心模块定义 4.2）。
    }
}
