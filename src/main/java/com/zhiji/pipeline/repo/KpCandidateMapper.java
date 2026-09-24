package com.zhiji.pipeline.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.pipeline.domain.KpCandidate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KpCandidateMapper extends BaseMapper<KpCandidate> {

    /** 统计文档下处于工作流（非 pending）的候选数：删除素材文档前的在途检查。 */
    @Select("SELECT COUNT(*) FROM kp_candidate WHERE doc_id = #{docId} AND status != 'pending'")
    int countInFlight(@Param("docId") long docId);

    /** 统计文档下已被归一吸收的候选数：即使仍是 pending 也不允许删素材（覆盖源数会被孤儿化）。 */
    @Select("SELECT COUNT(*) FROM kp_candidate WHERE doc_id = #{docId} AND canonical_id IS NOT NULL")
    int countAbsorbed(@Param("docId") long docId);

    /** 归一漏斗输入：未被归一吸收的散题（pending 且 canonical_id 为空）。 */
    @Select("SELECT * FROM kp_candidate WHERE status = 'pending' AND canonical_id IS NULL ORDER BY id")
    List<KpCandidate> selectUnabsorbedPending();

    /** 归一题名下候选（溯源视图 / 拆分回退 / 覆盖重算）。 */
    @Select("SELECT * FROM kp_candidate WHERE canonical_id = #{canonicalId} ORDER BY id")
    List<KpCandidate> selectByCanonicalId(@Param("canonicalId") long canonicalId);

    /** 拆题前清空该文档的待研判候选（重复拆题的幂等性保障）。 */
    @Delete("DELETE FROM kp_candidate WHERE doc_id = #{docId} AND status = 'pending'")
    int deletePendingByDocId(@Param("docId") long docId);
}
