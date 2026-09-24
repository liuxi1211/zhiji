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
 * 限额与熔断（D32 口径）：判定软上限不阻断、变体硬上限抛 SYS_RATE_LIMITED、
 * 单用户合计硬熔断；全站熔断为默认关闭的可选护栏（启用后仅统计 C 端消费类），
 * B 端生产类（profileId=0）不设限额不计熔断。
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
    void siteCircuitDisabledByDefaultDoesNotBlock() {
        // D32：默认 siteCircuitEnabled=false，C 端消费类即使超阈值也不熔断
        configService.saveLimits(new LlmLimits(200, 20, 300, 5, false));
        seed(LlmPurpose.EXAM_SUMMARY.getCode(), 4L, 10);
        assertThatCode(() -> rateGuard.check(LlmPurpose.EXAM_SUMMARY, 4L)).doesNotThrowAnyException();
    }

    @Test
    void siteCircuitEnabledOnlyCountsCustomerUsage() {
        // D32：护栏开启后，B 端生产类计数（profile_id=0）不计入，仅 C 端消费类触发
        configService.saveLimits(new LlmLimits(200, 20, 300, 5, true));
        seed(LlmPurpose.AI_COMPOSE.getCode(), 0L, 50);
        assertThatCode(() -> rateGuard.check(LlmPurpose.EXAM_SUMMARY, 4L)).doesNotThrowAnyException();

        seed(LlmPurpose.EXAM_SUMMARY.getCode(), 4L, 5);
        assertThatThrownBy(() -> rateGuard.check(LlmPurpose.EXAM_SUMMARY, 4L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("SYS_CIRCUIT_OPEN");
    }

    @Test
    void backendProductionPurposeNeverRateLimited() {
        // D32：B 端生产类（profileId=0）不受任何限额与熔断约束——即使护栏开启且用户配额耗尽
        configService.saveLimits(new LlmLimits(200, 20, 300, 5, true));
        seed(LlmPurpose.EXAM_SUMMARY.getCode(), 5L, LlmLimits.defaults().perProfileDailyTotal());
        assertThatCode(() -> rateGuard.check(LlmPurpose.SPLIT_FALLBACK, 0L)).doesNotThrowAnyException();
        assertThatCode(() -> rateGuard.check(LlmPurpose.DOC_FORM_DETECT, 0L)).doesNotThrowAnyException();
        assertThatCode(() -> rateGuard.check(LlmPurpose.QUESTION_GEN, 0L)).doesNotThrowAnyException();
        assertThatCode(() -> rateGuard.check(LlmPurpose.INTERVIEW_EXTRACT, 0L)).doesNotThrowAnyException();
    }
}
