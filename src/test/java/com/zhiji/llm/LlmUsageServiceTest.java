package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmUsageDaily;
import com.zhiji.llm.service.LlmUsageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 计数落库：upsert 自增、profile_id=0 无档案计数单行、当日与近 7 天聚合。
 */
@SpringBootTest
@ActiveProfiles("test")
class LlmUsageServiceTest {

    @Autowired
    LlmUsageService usageService;

    @BeforeEach
    void reset() {
        usageService.deleteByDate(usageService.today());
    }

    @Test
    void incrementsViaUpsert() {
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE.getCode(), 10L);
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE.getCode(), 10L);
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE.getCode(), 10L);
        assertThat(usageService.todayCallCount(LlmPurpose.JUDGE_PRACTICE.getCode(), 10L)).isEqualTo(3);
    }

    @Test
    void nullProfileCountsAsZeroWithSingleRow() {
        usageService.recordCall(LlmPurpose.AI_COMPOSE.getCode(), 0L);
        usageService.recordCall(LlmPurpose.AI_COMPOSE.getCode(), 0L);
        List<LlmUsageDaily> rows = usageService.todayRows().stream()
                .filter(r -> LlmPurpose.AI_COMPOSE.getCode().equals(r.getPurpose()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getProfileId()).isEqualTo(0L);
        assertThat(rows.get(0).getCallCount()).isEqualTo(2);
    }

    @Test
    void aggregatesByProfileAndSite() {
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE.getCode(), 20L);
        usageService.recordCall(LlmPurpose.EXAM_SUMMARY.getCode(), 20L);
        usageService.recordCall(LlmPurpose.AI_COMPOSE.getCode(), 0L);
        assertThat(usageService.todayTotalByProfile(20L)).isEqualTo(2);
        assertThat(usageService.todayTotal()).isEqualTo(3);
    }

    @Test
    void recentRowsCoverToday() {
        usageService.recordCall(LlmPurpose.CLUSTER_DEDUP.getCode(), 0L);
        assertThat(usageService.recentRows(7)).anyMatch(r ->
                LlmPurpose.CLUSTER_DEDUP.getCode().equals(r.getPurpose()));
    }

    @Test
    void countsFailures() {
        usageService.recordFail(LlmPurpose.SPLIT_FALLBACK.getCode(), 0L);
        assertThat(usageService.todayRows()).anyMatch(r ->
                LlmPurpose.SPLIT_FALLBACK.getCode().equals(r.getPurpose()) && r.getFailCount() == 1);
    }
}
