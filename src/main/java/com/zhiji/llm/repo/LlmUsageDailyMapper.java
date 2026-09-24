package com.zhiji.llm.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.llm.domain.LlmUsageDaily;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface LlmUsageDailyMapper extends BaseMapper<LlmUsageDaily> {

    /**
     * 计数自增（SQLite upsert）：同日同入口同档案不存在则插入，存在则 +1。
     * 先 INSERT OR IGNORE 建行再 UPDATE +1，避免 ON CONFLICT 语法在不同 SQLite 版本上的差异。
     */
    @Update("INSERT OR IGNORE INTO llm_usage_daily (stat_date, purpose, profile_id, call_count, fail_count) "
            + "VALUES (#{date}, #{purpose}, #{profileId}, 0, 0)")
    int ensureRow(@Param("date") String date, @Param("purpose") String purpose, @Param("profileId") long profileId);

    @Update("UPDATE llm_usage_daily SET call_count = call_count + 1, updated_at = datetime('now') "
            + "WHERE stat_date = #{date} AND purpose = #{purpose} AND profile_id = #{profileId}")
    int incrementCall(@Param("date") String date, @Param("purpose") String purpose, @Param("profileId") long profileId);

    @Update("UPDATE llm_usage_daily SET fail_count = fail_count + 1, updated_at = datetime('now') "
            + "WHERE stat_date = #{date} AND purpose = #{purpose} AND profile_id = #{profileId}")
    int incrementFail(@Param("date") String date, @Param("purpose") String purpose, @Param("profileId") long profileId);

    @Select("SELECT COALESCE(SUM(call_count), 0) FROM llm_usage_daily "
            + "WHERE stat_date = #{date} AND profile_id = #{profileId}")
    long sumByProfile(@Param("date") String date, @Param("profileId") long profileId);

    @Select("SELECT COALESCE(SUM(call_count), 0) FROM llm_usage_daily WHERE stat_date = #{date}")
    long sumByDate(@Param("date") String date);

    /** C 端消费类合计（D32 护栏统计口径：不含 B 端生产类 profile_id=0 哨兵行）。 */
    @Select("SELECT COALESCE(SUM(call_count), 0) FROM llm_usage_daily "
            + "WHERE stat_date = #{date} AND profile_id != 0")
    long sumCustomerByDate(@Param("date") String date);

    @Select("SELECT COALESCE(SUM(call_count), 0) FROM llm_usage_daily "
            + "WHERE stat_date = #{date} AND purpose = #{purpose} AND profile_id = #{profileId}")
    long sumByPurposeAndProfile(@Param("date") String date, @Param("purpose") String purpose,
            @Param("profileId") long profileId);

    @Select("SELECT * FROM llm_usage_daily WHERE stat_date = #{date} ORDER BY purpose, profile_id")
    List<LlmUsageDaily> listByDate(@Param("date") String date);

    @Select("SELECT * FROM llm_usage_daily WHERE stat_date >= #{fromDate} ORDER BY stat_date, purpose, profile_id")
    List<LlmUsageDaily> listSince(@Param("fromDate") String fromDate);
}
