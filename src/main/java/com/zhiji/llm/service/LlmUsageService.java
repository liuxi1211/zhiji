package com.zhiji.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmUsageDaily;
import com.zhiji.llm.repo.LlmAccountMapper;
import com.zhiji.llm.repo.LlmUsageDailyMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调用计数落库与聚合（计数主体键 = llm_usage_daily.profile_id 列）：
 * C 端主体 = 账号 id（sys_customer，业务层传入的档案 id 在此统一映射，映射结果进程内缓存——
 * 档案归属不变更）；B 端/无账号上下文 = 0 哨兵。
 * 计数用于两处：限流窗口判定（日/周/月）与管理台用量列表（C 端按账号、B 端按入口）。
 */
@Service
public class LlmUsageService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 日/周/月三窗口计数（自然窗口：今日 / 本周一至今 / 本月 1 号至今）。 */
    public record WindowCounts(long daily, long weekly, long monthly) {
    }

    private final LlmUsageDailyMapper usageMapper;
    private final LlmAccountMapper accountMapper;
    private final Map<Long, Long> accountCache = new ConcurrentHashMap<>();

    public LlmUsageService(LlmUsageDailyMapper usageMapper, LlmAccountMapper accountMapper) {
        this.usageMapper = usageMapper;
        this.accountMapper = accountMapper;
    }

    @Transactional
    public void recordCall(LlmPurpose purpose, long profileKey) {
        record(purpose, profileKey, true);
    }

    @Transactional
    public void recordFail(LlmPurpose purpose, long profileKey) {
        record(purpose, profileKey, false);
    }

    private void record(LlmPurpose purpose, long profileKey, boolean call) {
        String date = today();
        long subjectId = subjectKey(purpose, profileKey);
        usageMapper.ensureRow(date, purpose.getCode(), subjectId);
        if (call) {
            usageMapper.incrementCall(date, purpose.getCode(), subjectId);
        } else {
            usageMapper.incrementFail(date, purpose.getCode(), subjectId);
        }
    }

    /** 计数主体键：B 端 / 无档案上下文（0）统一落 0 哨兵；C 端按账号计数。 */
    public long subjectKey(LlmPurpose purpose, long profileKey) {
        if (purpose.side() == LlmPurpose.Side.B || profileKey == 0L) {
            return 0L;
        }
        return accountIdOf(profileKey);
    }

    /** 档案 → 账号映射（归属不变，查一次永久缓存；档案不存在回落 0，仅受全站熔断约束）。 */
    public long accountIdOf(long profileId) {
        return accountCache.computeIfAbsent(profileId, id -> {
            Long customerId = accountMapper.findCustomerIdByProfileId(id);
            return customerId == null ? 0L : customerId;
        });
    }

    /** 指定主体（账号或 0 哨兵）在侧别入口上的日/周/月窗口计数。 */
    public WindowCounts windowCounts(long subjectId, LlmPurpose.Side side) {
        LocalDate today = LocalDate.now();
        return new WindowCounts(
                sumBetween(today, today, subjectId, side),
                sumBetween(mondayOf(today), today, subjectId, side),
                sumBetween(monthStartOf(today), today, subjectId, side));
    }

    /** B 端全站三窗口计数（主体 0 + B 端入口）。 */
    public WindowCounts staffWindowCounts() {
        return windowCounts(0L, LlmPurpose.Side.B);
    }

    /**
     * C 端账号用量列表：本月窗口内按账号聚合出日/周/月计数（主体键 → 账号名由调用方补齐）。
     * key = 账号 id；主体 0（无账号上下文的 C 端调用）也会出现在结果里，展示为「—」。
     */
    public Map<Long, WindowCounts> customerWindowCounts() {
        LocalDate today = LocalDate.now();
        String todayStr = fmt(today);
        String mondayStr = fmt(mondayOf(today));
        String monthStartStr = fmt(monthStartOf(today));
        Map<Long, long[]> acc = new HashMap<>();
        for (LlmUsageDaily row : usageMapper.listBetweenByPurposes(monthStartStr, todayStr,
                LlmPurpose.codes(LlmPurpose.Side.C))) {
            long[] counts = acc.computeIfAbsent(row.getProfileId(), key -> new long[3]);
            long calls = row.getCallCount() == null ? 0L : row.getCallCount();
            if (todayStr.equals(row.getStatDate())) {
                counts[0] += calls;
            }
            if (row.getStatDate().compareTo(mondayStr) >= 0) {
                counts[1] += calls;
            }
            counts[2] += calls;
        }
        Map<Long, WindowCounts> result = new HashMap<>();
        acc.forEach((account, counts) -> result.put(account, new WindowCounts(counts[0], counts[1], counts[2])));
        return result;
    }

    /** 账号名映射（用量列表展示；0 哨兵/不存在的账号由调用方处理）。 */
    public Map<Long, String> accountNames(java.util.Collection<Long> accountIds) {
        List<Long> ids = accountIds.stream().filter(id -> id != 0L).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (com.zhiji.llm.domain.AccountName row : accountMapper.findNames(ids)) {
            names.put(row.getId(), row.getUsername());
        }
        return names;
    }

    private long sumBetween(LocalDate from, LocalDate to, long subjectId, LlmPurpose.Side side) {
        return usageMapper.sumBetweenBySubject(fmt(from), fmt(to), subjectId, LlmPurpose.codes(side));
    }

    private LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LocalDate monthStartOf(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    public long todayTotal() {
        return usageMapper.sumByDate(today());
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

    private String fmt(LocalDate date) {
        return date.format(DATE);
    }
}
