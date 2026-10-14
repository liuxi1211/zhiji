package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmRateGuard;
import com.zhiji.llm.service.LlmUsageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 限流与熔断（成本治理 2026-10 统一规划，三窗口全硬限）：
 * C 端按账号日/周/月硬限（跨档案合并到账号）、B 端全站总额度、全站日熔断。
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

    @Autowired
    JdbcTemplate jdbcTemplate;

    Long customerId;
    Long profileId;
    Long profile2Id;

    @BeforeEach
    void reset() {
        usageService.deleteByDate(usageService.today());
        configService.saveLimits(LlmLimits.defaults());
        jdbcTemplate.update("INSERT INTO sys_customer (username, password_hash) VALUES (?, 'x')",
                "guard-" + System.nanoTime());
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_customer WHERE username LIKE 'guard-%' ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO prf_profile (customer_id, name) VALUES (?, '守卫测试档案一')", customerId);
        profileId = jdbcTemplate.queryForObject(
                "SELECT id FROM prf_profile WHERE customer_id = ? ORDER BY id DESC LIMIT 1", Long.class, customerId);
    }

    @AfterEach
    void cleanup() {
        usageService.deleteByDate(usageService.today());
        configService.saveLimits(LlmLimits.defaults());
        if (profileId != null) {
            jdbcTemplate.update("DELETE FROM prf_profile WHERE id = ?", profileId);
        }
        if (profile2Id != null) {
            jdbcTemplate.update("DELETE FROM prf_profile WHERE id = ?", profile2Id);
        }
        if (customerId != null) {
            jdbcTemplate.update("DELETE FROM sys_customer WHERE id = ?", customerId);
        }
    }

    private long insertSecondProfile() {
        jdbcTemplate.update("INSERT INTO prf_profile (customer_id, name) VALUES (?, '守卫测试档案二')", customerId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM prf_profile WHERE customer_id = ? ORDER BY id DESC LIMIT 1", Long.class, customerId);
    }

    private String errorCodeOf(Runnable action) {
        try {
            action.run();
        } catch (BizException e) {
            return e.getCode();
        }
        return null;
    }

    @Test
    void customerDailyHardLimitBlocksJudgeToo() {
        // 判定类不再有软上限：账号日总量超限一律 429（业务层走降级自评）
        configService.saveLimits(new LlmLimits(2, 800, 2000, 300, 1200, 4000, 5000));
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        usageService.recordCall(LlmPurpose.EXAM_SUMMARY, profileId);
        assertThat(errorCodeOf(() -> rateGuard.check(LlmPurpose.JUDGE_PRACTICE, profileId)))
                .isEqualTo("SYS_RATE_LIMITED");
    }

    @Test
    void weeklyWindowEnforced() {
        configService.saveLimits(new LlmLimits(1000, 2, 2000, 300, 1200, 4000, 5000));
        usageService.recordCall(LlmPurpose.EXAM_SUMMARY, profileId);
        usageService.recordCall(LlmPurpose.EXAM_SUMMARY, profileId);
        assertThatThrownBy(() -> rateGuard.check(LlmPurpose.EXAM_SUMMARY, profileId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("本周");
    }

    @Test
    void sameAccountSharesQuotaAcrossProfiles() {
        profile2Id = insertSecondProfile();
        configService.saveLimits(new LlmLimits(2, 800, 2000, 300, 1200, 4000, 5000));
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profile2Id);
        // 两个档案同属一个账号：额度合并计算，第二次档案的调用同样受限
        assertThat(errorCodeOf(() -> rateGuard.check(LlmPurpose.EXAM_SUMMARY, profile2Id)))
                .isEqualTo("SYS_RATE_LIMITED");
    }

    @Test
    void withinQuotaDoesNotBlock() {
        configService.saveLimits(new LlmLimits(100, 800, 2000, 300, 1200, 4000, 5000));
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        assertThatCode(() -> rateGuard.check(LlmPurpose.JUDGE_PRACTICE, profileId))
                .doesNotThrowAnyException();
    }

    @Test
    void staffSiteQuotaThrowsWithAdminHint() {
        configService.saveLimits(new LlmLimits(200, 800, 2000, 1, 1200, 4000, 5000));
        usageService.recordCall(LlmPurpose.SOURCE_REWRITE, 0L);
        assertThatThrownBy(() -> rateGuard.check(LlmPurpose.SOURCE_REWRITE, 0L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B 端")
                .hasMessageContaining("联系管理员");
    }

    @Test
    void siteCircuitBreakerCoversBothSides() {
        configService.saveLimits(new LlmLimits(200, 800, 2000, 300, 1200, 4000, 5));
        for (int i = 0; i < 5; i++) {
            usageService.recordCall(LlmPurpose.EXAM_SUMMARY, profileId);
        }
        assertThat(errorCodeOf(() -> rateGuard.check(LlmPurpose.EXAM_SUMMARY, profileId)))
                .isEqualTo("SYS_CIRCUIT_OPEN");
        assertThat(errorCodeOf(() -> rateGuard.check(LlmPurpose.SOURCE_REWRITE, 0L)))
                .isEqualTo("SYS_CIRCUIT_OPEN");
    }
}
