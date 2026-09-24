package com.zhiji.kb.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.kb.domain.KpMain;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface KpMainMapper extends BaseMapper<KpMain> {

    @Select("SELECT * FROM kp_main WHERE cluster_id = #{clusterId} ORDER BY difficulty, id")
    List<KpMain> listByCluster(@Param("clusterId") long clusterId);

    @Update("UPDATE kp_main SET cluster_id = #{targetClusterId}, updated_at = datetime('now') "
            + "WHERE cluster_id = #{fromClusterId}")
    int moveAllToCluster(@Param("fromClusterId") long fromClusterId,
            @Param("targetClusterId") long targetClusterId);

    String LIST_SELECT = "SELECT k.id, k.title, k.cluster_id, c.domain_id, d.name AS domain_name, "
            + "c.name AS cluster_name, k.sub_type, k.difficulty, k.status, k.baseline_levels, "
            + "k.last_reviewed_at, k.review_due_at "
            + "FROM kp_main k JOIN kp_cluster c ON c.id = k.cluster_id "
            + "LEFT JOIN skill_domain d ON d.id = c.domain_id";

    /**
     * 动态筛选分页：<script> 拼 <where>；reviewDue 为 true 时过滤已到期
     * （review_due_at 非空且 <= 当前 UTC 时间，与库内 datetime('now') 口径一致）。
     */
    @Select("<script>" + LIST_SELECT
            + " <where>"
            + " <if test=\"keyword != null\"> AND k.title LIKE #{keyword} </if>"
            + " <if test=\"domainId != null\"> AND c.domain_id = #{domainId} </if>"
            + " <if test=\"clusterId != null\"> AND k.cluster_id = #{clusterId} </if>"
            + " <if test=\"subType != null\"> AND k.sub_type = #{subType} </if>"
            + " <if test=\"status != null\"> AND k.status = #{status} </if>"
            + " <if test=\"difficulty != null\"> AND k.difficulty = #{difficulty} </if>"
            + " <if test=\"baseline != null and baseline == 'marked'\"> AND k.baseline_levels != '' </if>"
            + " <if test=\"baseline != null and baseline == 'unmarked'\"> AND k.baseline_levels = '' </if>"
            + " <if test=\"reviewDue != null and reviewDue\">"
            + "   AND k.review_due_at IS NOT NULL AND k.review_due_at &lt;= datetime('now')"
            + " </if>"
            + " </where>"
            + " ORDER BY k.id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<KpListRow> selectKpList(@Param("keyword") String keyword, @Param("domainId") Long domainId,
            @Param("clusterId") Long clusterId, @Param("subType") String subType,
            @Param("status") String status, @Param("difficulty") Integer difficulty,
            @Param("baseline") String baseline, @Param("reviewDue") Boolean reviewDue,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM kp_main k JOIN kp_cluster c ON c.id = k.cluster_id "
            + " <where>"
            + " <if test=\"keyword != null\"> AND k.title LIKE #{keyword} </if>"
            + " <if test=\"domainId != null\"> AND c.domain_id = #{domainId} </if>"
            + " <if test=\"clusterId != null\"> AND k.cluster_id = #{clusterId} </if>"
            + " <if test=\"subType != null\"> AND k.sub_type = #{subType} </if>"
            + " <if test=\"status != null\"> AND k.status = #{status} </if>"
            + " <if test=\"difficulty != null\"> AND k.difficulty = #{difficulty} </if>"
            + " <if test=\"baseline != null and baseline == 'marked'\"> AND k.baseline_levels != '' </if>"
            + " <if test=\"baseline != null and baseline == 'unmarked'\"> AND k.baseline_levels = '' </if>"
            + " <if test=\"reviewDue != null and reviewDue\">"
            + "   AND k.review_due_at IS NOT NULL AND k.review_due_at &lt;= datetime('now')"
            + " </if>"
            + " </where>"
            + "</script>")
    long countKpList(@Param("keyword") String keyword, @Param("domainId") Long domainId,
            @Param("clusterId") Long clusterId, @Param("subType") String subType,
            @Param("status") String status, @Param("difficulty") Integer difficulty,
            @Param("baseline") String baseline, @Param("reviewDue") Boolean reviewDue);

    /** 域字典下拉：kpCount 统计域下经簇的非 retired 知识点（join 聚合不进实体，见 CLAUDE.md 陷阱 E）。 */
    @Select("SELECT d.id, d.name, d.status, "
            + "(SELECT COUNT(*) FROM kp_main k JOIN kp_cluster c ON c.id = k.cluster_id "
            + "  WHERE c.domain_id = d.id AND k.status != 'retired') AS kp_count "
            + "FROM skill_domain d ORDER BY d.sort_order, d.id")
    List<MetaDomainRow> selectMetaDomains();

    @Select("SELECT id, name, domain_id, status FROM kp_cluster ORDER BY id")
    List<MetaClusterRow> selectMetaClusters();

    /**
     * 列表投影：domain_name / cluster_name 是 join 出的列，没有实体列承接，
     * 用独立行对象 + map-underscore-to-camel-case 映射。
     */
    class KpListRow {

        private Long id;
        private String title;
        private Long clusterId;
        private Long domainId;
        private String domainName;
        private String clusterName;
        private String subType;
        private Integer difficulty;
        private String status;
        private String baselineLevels;
        private String lastReviewedAt;
        private String reviewDueAt;

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

        public Long getClusterId() {
            return clusterId;
        }

        public void setClusterId(Long clusterId) {
            this.clusterId = clusterId;
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

        public String getClusterName() {
            return clusterName;
        }

        public void setClusterName(String clusterName) {
            this.clusterName = clusterName;
        }

        public String getSubType() {
            return subType;
        }

        public void setSubType(String subType) {
            this.subType = subType;
        }

        public Integer getDifficulty() {
            return difficulty;
        }

        public void setDifficulty(Integer difficulty) {
            this.difficulty = difficulty;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getBaselineLevels() {
            return baselineLevels;
        }

        public void setBaselineLevels(String baselineLevels) {
            this.baselineLevels = baselineLevels;
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
    }

    class MetaDomainRow {

        private Long id;
        private String name;
        private String status;
        private Long kpCount;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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
    }

    class MetaClusterRow {

        private Long id;
        private String name;
        private Long domainId;
        private String status;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getDomainId() {
            return domainId;
        }

        public void setDomainId(Long domainId) {
            this.domainId = domainId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
