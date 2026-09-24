package com.zhiji.kb.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.kb.domain.KpPrereq;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KpPrereqMapper extends BaseMapper<KpPrereq> {

    @Select("SELECT * FROM kp_prereq ORDER BY kp_id, id")
    List<KpPrereq> selectAll();

    @Select("SELECT * FROM kp_prereq WHERE kp_id = #{kpId} ORDER BY id")
    List<KpPrereq> selectByKpId(@Param("kpId") long kpId);

    @Delete("DELETE FROM kp_prereq WHERE kp_id = #{kpId}")
    int deleteByKpId(@Param("kpId") long kpId);

    /**
     * 前置清单投影：join 出的 title 没有实体列承接，用独立行对象 + map-underscore-to-camel-case
     * 映射（CLAUDE.md 陷阱 E：join 投影不进实体）。
     */
    @Select("SELECT p.id, p.title FROM kp_prereq r JOIN kp_main p ON p.id = r.prereq_kp_id "
            + "WHERE r.kp_id = #{kpId} ORDER BY p.id")
    List<PrereqItemRow> selectPrereqItems(@Param("kpId") long kpId);

    class PrereqItemRow {

        private Long id;
        private String title;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }
}
