package com.zhiji.kb.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.kb.domain.KpCluster;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ClusterMapper extends BaseMapper<KpCluster> {

    String LIST_SELECT = "SELECT c.id, c.domain_id, d.name AS domain_name, c.name, c.description, c.status, "
            + "(SELECT COUNT(*) FROM kp_main k WHERE k.cluster_id = c.id AND k.status != 'retired') AS kp_count, "
            + "c.last_reviewed_at, c.review_due_at, c.created_at, c.updated_at "
            + "FROM kp_cluster c LEFT JOIN skill_domain d ON d.id = c.domain_id";

    @Select(LIST_SELECT + " WHERE (#{domainId} IS NULL OR c.domain_id = #{domainId}) "
            + "AND (#{status} IS NULL OR c.status = #{status}) ORDER BY c.id DESC")
    List<ClusterListRow> selectClusterList(@Param("domainId") Long domainId, @Param("status") String status);

    @Select("SELECT d.name FROM skill_domain d WHERE d.id = #{domainId}")
    String selectDomainName(@Param("domainId") long domainId);

    @Select("SELECT COUNT(*) FROM skill_domain d WHERE d.id = #{domainId} AND d.status = 'active'")
    int countActiveDomain(@Param("domainId") long domainId);

    /**
     * 列表投影：join 出的 domain_name / 聚合的 kp_count 没有实体列承接，
     * 用独立行对象 + map-underscore-to-camel-case 映射（CLAUDE.md 陷阱 E：聚合字段不进实体）。
     */
    class ClusterListRow {

        private Long id;
        private Long domainId;
        private String domainName;
        private String name;
        private String description;
        private String status;
        private Long kpCount;
        private String lastReviewedAt;
        private String reviewDueAt;
        private String createdAt;
        private String updatedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getDomainId() {
            return domainId;
        }

        public void setDomainId(Long domainId) {
            this.domainId = domainId;
        }

        public String getDomainName() {
            return domainName;
        }

        public void setDomainName(String domainName) {
            this.domainName = domainName;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getKpCount() {
            return kpCount;
        }

        public void setKpCount(Long kpCount) {
            this.kpCount = kpCount;
        }

        public String getLastReviewedAt() {
            return lastReviewedAt;
        }

        public void setLastReviewedAt(String lastReviewedAt) {
            this.lastReviewedAt = lastReviewedAt;
        }

        public String getReviewDueAt() {
            return reviewDueAt;
        }

        public void setReviewDueAt(String reviewDueAt) {
            this.reviewDueAt = reviewDueAt;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
