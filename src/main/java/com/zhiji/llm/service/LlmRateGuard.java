package com.zhiji.llm.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import org.springframework.stereotype.Service;

/**
 * 限额与熔断守卫（核心模块定义 4.2 成本治理，D32 修订口径）。
 *
 * D32 限额分家：B 端生产类（profileId=0 哨兵：拆题兜底 / 聚类去重 / AI 成稿 / 形态识别 /
 * 反推出题 / 面经抽题 / embedding）不设限额、不计任何熔断——B 端人工触发即天然节流阀；
 * C 端消费类受单用户（名下全部档案）限额约束；全站每日总熔断降级为默认关闭的可选护栏，
 * 开启后仅统计 C 端消费类计数。多账号滥用的总量风险由用量观测承担。
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
        // D32：B 端生产类入口（无档案哨兵 0）直接放行，不设限额不计熔断
        if (profileId == 0L) {
            return;
        }
        LlmLimits limits = configService.limits();

        // 可选护栏（默认关闭）：启用时仅统计 C 端消费类（profile_id != 0）
        if (limits.siteCircuitEnabled()
                && usageService.todayCustomerTotal() >= limits.siteDailyTotal()) {
            throw BizException.of(429, "SYS_CIRCUIT_OPEN", "今日服务调用量已达上限，请明日再试");
        }

        if (usageService.todayTotalByProfile(profileId) >= limits.perProfileDailyTotal()) {
            throw BizException.of(429, "SYS_RATE_LIMITED", "今日调用次数已达上限，可先自评");
        }

        if (purpose.limitKey() == LlmPurpose.LimitKey.VARIANT
                && usageService.todayCallCount(purpose.getCode(), profileId) >= limits.variantRewriteDaily()) {
            throw BizException.of(429, "SYS_RATE_LIMITED", "今日变体改写次数已达上限");
        }

        // judgeDaily 为软上限：超限不阻断，仅在用量视图标记告警（核心模块定义 4.2）。
    }
}
