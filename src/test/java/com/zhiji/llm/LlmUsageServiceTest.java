package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmUsageDaily;
import com.zhiji.llm.service.LlmUsageService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 计数落库（计数主体键 = llm_usage_daily.profile_id 列）：
 * upsert 自增、C 端主体为账号 id（档案 → 账号映射）、B 端/无上下文 0 哨兵单行、窗口聚合。
 */
@SpringBootTest
@ActiveProfiles("test")
class LlmUsageServiceTest {

    @Autowired
    LlmUsageService usageService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    Long customerId;
    Long profileId;

    @BeforeEach
    void reset() {
        usageService.deleteByDate(usageService.today());
        jdbcTemplate.update("INSERT INTO sys_customer (username, password_hash) VALUES (?, 'x')",
                "usage-" + System.nanoTime());
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_customer WHERE username LIKE 'usage-%' ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO prf_profile (customer_id, name) VALUES (?, '限流测试档案')", customerId);
        profileId = jdbcTemplate.queryForObject(
                "SELECT id FROM prf_profile WHERE customer_id = ? ORDER BY id DESC LIMIT 1", Long.class, customerId);
    }

    @AfterEach
    void cleanup() {
        usageService.deleteByDate(usageService.today());
        if (profileId != null) {
            jdbcTemplate.update("DELETE FROM prf_profile WHERE id = ?", profileId);
        }
        if (customerId != null) {
            jdbcTemplate.update("DELETE FROM sys_customer WHERE id = ?", customerId);
        }
    }

    @Test
    void incrementsViaUpsertUnderAccountKey() {
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        long accountId = usageService.accountIdOf(profileId);
        assertThat(accountId).isEqualTo(customerId);
        assertThat(usageService.windowCounts(accountId, LlmPurpose.Side.C).daily()).isEqualTo(3);
    }

    @Test
    void customerRowsCarryAccountIdNotProfileId() {
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        List<LlmUsageDaily> rows = usageService.todayRows().stream()
                .filter(r -> LlmPurpose.JUDGE_PRACTICE.getCode().equals(r.getPurpose()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getProfileId()).isEqualTo(customerId).isNotEqualTo(profileId);
    }

    @Test
    void zeroKeyCountsAsSingleSentinelRow() {
        usageService.recordCall(LlmPurpose.AI_COMPOSE, 0L);
        usageService.recordCall(LlmPurpose.AI_COMPOSE, 0L);
        List<LlmUsageDaily> rows = usageService.todayRows().stream()
                .filter(r -> LlmPurpose.AI_COMPOSE.getCode().equals(r.getPurpose()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getProfileId()).isEqualTo(0L);
        assertThat(rows.get(0).getCallCount()).isEqualTo(2);
    }

    @Test
    void staffWindowCountsCoverStaffPurposesOnly() {
        usageService.recordCall(LlmPurpose.SOURCE_REWRITE, 0L);
        usageService.recordCall(LlmPurpose.SOURCE_TITLE, 0L);
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        assertThat(usageService.staffWindowCounts().daily()).isEqualTo(2);
    }

    @Test
    void customerWindowCountsAggregateDayWeekMonth() {
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
        usageService.recordCall(LlmPurpose.EXAM_SUMMARY, profileId);
        usageService.recordCall(LlmPurpose.AI_COMPOSE, profileId);
        var counts = usageService.customerWindowCounts().get(customerId);
        assertThat(counts).isNotNull();
        // 同一天：三窗口计数相同；跨窗口口径由窗口起点的 SQL 区间保证
        assertThat(counts.daily()).isEqualTo(3);
        assertThat(counts.weekly()).isEqualTo(3);
        assertThat(counts.monthly()).isEqualTo(3);
    }

    @Test
    void recentRowsCoverToday() {
        usageService.recordCall(LlmPurpose.CLUSTER_DEDUP, 0L);
        assertThat(usageService.recentRows(7)).anyMatch(r ->
                LlmPurpose.CLUSTER_DEDUP.getCode().equals(r.getPurpose()));
    }

    @Test
    void countsFailures() {
        usageService.recordFail(LlmPurpose.SPLIT_FALLBACK, 0L);
        assertThat(usageService.todayRows()).anyMatch(r ->
                LlmPurpose.SPLIT_FALLBACK.getCode().equals(r.getPurpose()) && r.getFailCount() == 1);
    }
}
