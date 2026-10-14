package com.zhiji.llm.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.llm.domain.LlmUsageDaily;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface LlmUsageDailyMapper extends BaseMapper<LlmUsageDaily> {

    /**
     * 计数自增（SQLite upsert）：同日同入口同主体不存在则插入，存在则 +1。
     * 先 INSERT OR IGNORE 建行再 UPDATE +1，避免 ON CONFLICT 语法在不同 SQLite 版本上的差异。
     * profile_id 列为「计数主体键」：C 端 = 账号 id（sys_customer），B 端/无上下文 = 0。
     */
    @Update("INSERT OR IGNORE INTO llm_usage_daily (stat_date, purpose, profile_id, call_count, fail_count) "
            + "VALUES (#{date}, #{purpose}, #{subjectId}, 0, 0)")
    int ensureRow(@Param("date") String date, @Param("purpose") String purpose, @Param("subjectId") long subjectId);

    @Update("UPDATE llm_usage_daily SET call_count = call_count + 1, updated_at = datetime('now') "
            + "WHERE stat_date = #{date} AND purpose = #{purpose} AND profile_id = #{subjectId}")
    int incrementCall(@Param("date") String date, @Param("purpose") String purpose, @Param("subjectId") long subjectId);

    @Update("UPDATE llm_usage_daily SET fail_count = fail_count + 1, updated_at = datetime('now') "
            + "WHERE stat_date = #{date} AND purpose = #{purpose} AND profile_id = #{subjectId}")
    int incrementFail(@Param("date") String date, @Param("purpose") String purpose, @Param("subjectId") long subjectId);

    /** 全站当日合计（全站熔断口径，C+B 所有行）。 */
    @Select("SELECT COALESCE(SUM(call_count), 0) FROM llm_usage_daily WHERE stat_date = #{date}")
    long sumByDate(@Param("date") String date);

    /** 指定主体在日期区间内的调用合计（限流窗口：日 / 周 / 月，区间 [fromDate, toDate]）。 */
    @Select("<script>SELECT COALESCE(SUM(call_count), 0) FROM llm_usage_daily "
            + "WHERE stat_date BETWEEN #{fromDate} AND #{toDate} AND profile_id = #{subjectId} "
            + "AND purpose IN <foreach item='p' collection='purposes' open='(' separator=',' close=')'>#{p}</foreach></script>")
    long sumBetweenBySubject(@Param("fromDate") String fromDate, @Param("toDate") String toDate,
            @Param("subjectId") long subjectId, @Param("purposes") List<String> purposes);

    /** 日期区间内的原始计数行（按主体键聚合出用量列表）。 */
    @Select("<script>SELECT * FROM llm_usage_daily WHERE stat_date BETWEEN #{fromDate} AND #{toDate} "
            + "AND purpose IN <foreach item='p' collection='purposes' open='(' separator=',' close=')'>#{p}</foreach> "
            + "ORDER BY stat_date, purpose, profile_id</script>")
    List<LlmUsageDaily> listBetweenByPurposes(@Param("fromDate") String fromDate, @Param("toDate") String toDate,
            @Param("purposes") List<String> purposes);

    @Select("SELECT * FROM llm_usage_daily WHERE stat_date = #{date} ORDER BY purpose, profile_id")
    List<LlmUsageDaily> listByDate(@Param("date") String date);

    @Select("SELECT * FROM llm_usage_daily WHERE stat_date >= #{fromDate} ORDER BY stat_date, purpose, profile_id")
    List<LlmUsageDaily> listSince(@Param("fromDate") String fromDate);
}
