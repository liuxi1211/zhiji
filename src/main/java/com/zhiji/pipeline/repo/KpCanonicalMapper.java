package com.zhiji.pipeline.repo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiji.pipeline.domain.KpCanonical;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KpCanonicalMapper extends BaseMapper<KpCanonical> {

    /** 覆盖率按簇聚合（知识地图排行：归一题数 / 覆盖源合计 / 已建设数；聚合列不进实体，陷阱 E）。 */
    @Select("SELECT c.cluster_id, x.name AS cluster_name, x.domain_id, d.name AS domain_name, "
            + "COUNT(*) AS canonical_count, SUM(c.coverage_count) AS coverage_sum, "
            + "SUM(CASE WHEN c.status = 'built' THEN 1 ELSE 0 END) AS built_count "
            + "FROM kp_canonical c "
            + "JOIN kp_cluster x ON x.id = c.cluster_id "
            + "LEFT JOIN skill_domain d ON d.id = x.domain_id "
            + "GROUP BY c.cluster_id, x.name, x.domain_id, d.name "
            + "ORDER BY coverage_sum DESC")
    List<ClusterCoverageRow> selectClusterCoverages();

    /** 未归簇的归一题数（覆盖率统计的「待聚簇」口径）。 */
    @Select("SELECT COUNT(*) FROM kp_canonical WHERE cluster_id IS NULL")
    int countUnclustered();

    class ClusterCoverageRow {

        private Long clusterId;
        private String clusterName;
        private Long domainId;
        private String domainName;
        private Long canonicalCount;
        private Long coverageSum;
        private Long builtCount;

        public Long getClusterId() {
            return clusterId;
        }

        public void setClusterId(Long clusterId) {
            this.clusterId = clusterId;
        }

        public String getClusterName() {
            return clusterName;
        }

        public void setClusterName(String clusterName) {
            this.clusterName = clusterName;
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

        public Long getCanonicalCount() {
            return canonicalCount;
        }

        public void setCanonicalCount(Long canonicalCount) {
            this.canonicalCount = canonicalCount;
        }

        public Long getCoverageSum() {
            return coverageSum;
        }

        public void setCoverageSum(Long coverageSum) {
            this.coverageSum = coverageSum;
        }

        public Long getBuiltCount() {
            return builtCount;
        }

        public void setBuiltCount(Long builtCount) {
            this.builtCount = builtCount;
        }
    }
}
