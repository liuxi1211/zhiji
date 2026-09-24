package com.zhiji.pipeline.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.pipeline.domain.KpCandidate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KpCandidateMapper extends BaseMapper<KpCandidate> {

    /** 统计文档下处于工作流（非 pending）的候选数：删除素材文档前的在途检查。 */
    @Select("SELECT COUNT(*) FROM kp_candidate WHERE doc_id = #{docId} AND status != 'pending'")
    int countInFlight(@Param("docId") long docId);

    /** 拆题前清空该文档的待研判候选（重复拆题的幂等性保障）。 */
    @Delete("DELETE FROM kp_candidate WHERE doc_id = #{docId} AND status = 'pending'")
    int deletePendingByDocId(@Param("docId") long docId);
}
