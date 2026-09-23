package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmRateGuard;
import com.zhiji.llm.service.LlmUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 限额与熔断：判定软上限不阻断、变体硬上限抛 SYS_RATE_LIMITED、
 * 单档案合计硬熔断、全站熔断抛 SYS_CIRCUIT_OPEN。
 */
@SpringBootTest
@ActiveProfiles("test")
class LlmRateGuardTest {

    @Autowired
    LlmRateGuard rateGuard;

    @Autowired
    LlmConfigService configService;

    @Autowired
    LlmUsageService usageService;

    @BeforeEach
    void reset() {
        usageService.deleteByDate(usageService.today());
        configService.saveLimits(LlmLimits.defaults());
    }

    private void seed(String purpose, long profileId, long count) {
        for (long i = 0; i < count; i++) {
            usageService.recordCall(purpose, profileId);
        }
    }

    @Test
    void judgeSoftLimitDoesNotBlock() {
        seed(LlmPurpose.JUDGE_PRACTICE.getCode(), 1L, LlmLimits.defaults().judgeDaily());
        assertThatCode(() -> rateGuard.check(LlmPurpose.JUDGE_PRACTICE, 1L)).doesNotThrowAnyException();
    }

    @Test
    void variantHardLimitThrowsRateLimited() {
        seed(LlmPurpose.VARIANT_REWRITE.getCode(), 2L, LlmLimits.defaults().variantRewriteDaily());
        assertThatThrownBy(() -> rateGuard.check(LlmPurpose.VARIANT_REWRITE, 2L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("SYS_RATE_LIMITED");
    }

    @Test
    void perProfileHardLimitThrowsRateLimited() {
        seed(LlmPurpose.EXAM_SUMMARY.getCode(), 3L, LlmLimits.defaults().perProfileDailyTotal());
        assertThatThrownBy(() -> rateGuard.check(LlmPurpose.EXAM_SUMMARY, 3L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("SYS_RATE_LIMITED");
    }

    @Test
    void siteCircuitBreakerThrowsCircuitOpen() {
        configService.saveLimits(new LlmLimits(200, 20, 300, 5));
        seed(LlmPurpose.EXAM_SUMMARY.getCode(), 4L, 5);
        assertThatThrownBy(() -> rateGuard.check(LlmPurpose.EXAM_SUMMARY, 4L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("SYS_CIRCUIT_OPEN");
    }
}
