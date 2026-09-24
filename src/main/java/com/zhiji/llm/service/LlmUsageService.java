package com.zhiji.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.llm.domain.LlmUsageDaily;
import com.zhiji.llm.repo.LlmUsageDailyMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调用计数（入口 × 天）落库与聚合。profile_id = 0 为无档案（B 端人工触发 / 全站）。
 * 计数用于两处：设置页/概览页用量对照，以及限额与熔断判定。
 */
@Service
public class LlmUsageService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final LlmUsageDailyMapper usageMapper;

    public LlmUsageService(LlmUsageDailyMapper usageMapper) {
        this.usageMapper = usageMapper;
    }

    @Transactional
    public void recordCall(String purpose, long profileId) {
        String date = today();
        usageMapper.ensureRow(date, purpose, profileId);
        usageMapper.incrementCall(date, purpose, profileId);
    }

    @Transactional
    public void recordFail(String purpose, long profileId) {
        String date = today();
        usageMapper.ensureRow(date, purpose, profileId);
        usageMapper.incrementFail(date, purpose, profileId);
    }

    public long todayCallCount(String purpose, long profileId) {
        return usageMapper.sumByPurposeAndProfile(today(), purpose, profileId);
    }

    public long todayTotalByProfile(long profileId) {
        return usageMapper.sumByProfile(today(), profileId);
    }

    public long todayTotal() {
        return usageMapper.sumByDate(today());
    }

    /** C 端消费类当日合计（D32 护栏口径：不含 B 端生产类 profile_id=0 哨兵）。 */
    public long todayCustomerTotal() {
        return usageMapper.sumCustomerByDate(today());
    }

    public List<LlmUsageDaily> todayRows() {
        return usageMapper.listByDate(today());
    }

    public List<LlmUsageDaily> recentRows(int days) {
        return usageMapper.listSince(LocalDate.now().minusDays(days - 1L).format(DATE));
    }

    public String today() {
        return LocalDate.now().format(DATE);
    }

    /** 供测试或维护场景清理指定日期计数。 */
    @Transactional
    public void deleteByDate(String date) {
        usageMapper.delete(new LambdaQueryWrapper<LlmUsageDaily>().eq(LlmUsageDaily::getStatDate, date));
    }
}
