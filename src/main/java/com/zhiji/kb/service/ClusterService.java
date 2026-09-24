package com.zhiji.kb.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.ContentStatus;
import com.zhiji.kb.domain.KpCluster;
import com.zhiji.kb.domain.KpMain;
import com.zhiji.kb.repo.ClusterMapper;
import com.zhiji.kb.repo.KpMainMapper;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClusterService {

    /**
     * 复核周期 90 天。写库时间用 UTC——schema 里 datetime('now') 默认值就是 UTC，
     * Java 侧同样取 UTC 再格式化，两边口径一致。
     */
    private static final Period REVIEW_PERIOD = Period.ofDays(90);

    private static final DateTimeFormatter SQLITE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ClusterMapper clusterMapper;
    private final KpMainMapper kpMainMapper;

    public ClusterService(ClusterMapper clusterMapper, KpMainMapper kpMainMapper) {
        this.clusterMapper = clusterMapper;
        this.kpMainMapper = kpMainMapper;
    }

    public record ClusterView(long id, long domainId, String domainName, String name, String description,
            String status, long kpCount, String lastReviewedAt, String reviewDueAt,
            String createdAt, String updatedAt) {
    }

    public record ClusterKpItem(long id, String title, String status, int difficulty) {
    }

    public record ClusterDetailView(long id, long domainId, String domainName, String name, String description,
            String zeroShotMd, String referenceSolutionMd, String status, Long mergedInto,
            String lastReviewedAt, String reviewDueAt, String updatedAt, List<ClusterKpItem> kpList) {
    }

    public List<ClusterView> list(Long domainId, String status) {
        List<ClusterMapper.ClusterListRow> rows = clusterMapper.selectClusterList(domainId, status);
        return rows.stream().map(r -> new ClusterView(r.getId(), r.getDomainId(), r.getDomainName(), r.getName(),
                r.getDescription(), r.getStatus(), r.getKpCount() == null ? 0 : r.getKpCount(),
                r.getLastReviewedAt(), r.getReviewDueAt(), r.getCreatedAt(), r.getUpdatedAt())).toList();
    }

    public ClusterDetailView detail(long id) {
        KpCluster cluster = requireCluster(id);
        String domainName = clusterMapper.selectDomainName(cluster.getDomainId());
        List<ClusterKpItem> kpList = kpMainMapper.listByCluster(id).stream()
                .map(kp -> new ClusterKpItem(kp.getId(), kp.getTitle(), kp.getStatus(), kp.getDifficulty()))
                .toList();
        return new ClusterDetailView(cluster.getId(), cluster.getDomainId(), domainName, cluster.getName(),
                cluster.getDescription(), cluster.getZeroShotMd(), cluster.getReferenceSolutionMd(),
                cluster.getStatus(), cluster.getMergedInto(), cluster.getLastReviewedAt(),
                cluster.getReviewDueAt(), cluster.getUpdatedAt(), kpList);
    }

    @Transactional
    public long create(Long domainId, String name, String description) {
        requireActiveDomain(domainId);
        KpCluster cluster = new KpCluster();
        cluster.setDomainId(domainId);
        cluster.setName(name);
        cluster.setDescription(description);
        cluster.setStatus(ContentStatus.DRAFT.getCode());
        clusterMapper.insert(cluster);
        return cluster.getId();
    }

    @Transactional
    public void update(long id, String name, Long domainId, String description, String zeroShotMd,
            String referenceSolutionMd) {
        KpCluster cluster = requireCluster(id);
        if (domainId != null && !domainId.equals(cluster.getDomainId())) {
            requireActiveDomain(domainId);
            cluster.setDomainId(domainId);
        }
        if (name != null) {
            cluster.setName(name);
        }
        if (description != null) {
            cluster.setDescription(description);
        }
        if (zeroShotMd != null) {
            cluster.setZeroShotMd(zeroShotMd);
        }
        if (referenceSolutionMd != null) {
            cluster.setReferenceSolutionMd(referenceSolutionMd);
        }
        clusterMapper.updateById(cluster);
    }

    /**
     * 只进不退：draft→reviewed→active。retired 只能从合并进（状态机入口唯一），
     * 推进到 reviewed/active 视为一次校对完成，刷新复核时间。
     */
    @Transactional
    public void changeStatus(long id, String targetStatus) {
        KpCluster cluster = requireCluster(id);
        ContentStatus current = ContentStatus.fromCode(cluster.getStatus());
        ContentStatus target = ContentStatus.fromCode(targetStatus);
        if (target == ContentStatus.REVIEWED && current != ContentStatus.DRAFT
                || target == ContentStatus.ACTIVE && current != ContentStatus.REVIEWED) {
            throw BizException.of(400, "KB_STATUS_ILLEGAL", "状态只允许 draft→reviewed→active 逐步推进，当前："
                    + cluster.getStatus() + "，目标：" + targetStatus);
        }
        if (target == ContentStatus.REVIEWED || target == ContentStatus.ACTIVE) {
            String now = nowText();
            cluster.setLastReviewedAt(now);
            cluster.setReviewDueAt(nowTextPlus(REVIEW_PERIOD));
        }
        cluster.setStatus(target.getCode());
        clusterMapper.updateById(cluster);
    }

    /**
     * 簇合并：知识点整体迁移（含 retired），原簇 retired 并保留 merged_into 指向。
     * 同一事务保证「迁移完成 + 原簇下线」要么都成要么都不成。
     */
    @Transactional
    public void merge(long id, long targetId) {
        KpCluster source = requireCluster(id);
        KpCluster target = requireCluster(targetId);
        if (id == targetId) {
            throw BizException.of(400, "KB_MERGE_INVALID", "合并目标不能是簇自身");
        }
        if (ContentStatus.RETIRED.getCode().equals(source.getStatus())) {
            throw BizException.of(400, "KB_MERGE_INVALID", "已下线的簇不能再合并");
        }
        if (ContentStatus.RETIRED.getCode().equals(target.getStatus())) {
            throw BizException.of(400, "KB_MERGE_INVALID", "合并目标已下线");
        }
        clusterMapper.updateById(markRetired(source, targetId));
        kpMainMapper.moveAllToCluster(id, targetId);
    }

    private KpCluster markRetired(KpCluster source, long targetId) {
        source.setStatus(ContentStatus.RETIRED.getCode());
        source.setMergedInto(targetId);
        return source;
    }

    private KpCluster requireCluster(long id) {
        KpCluster cluster = clusterMapper.selectById(id);
        if (cluster == null) {
            throw BizException.notFound("簇不存在或已删除：" + id);
        }
        return cluster;
    }

    private void requireActiveDomain(Long domainId) {
        if (domainId == null || clusterMapper.countActiveDomain(domainId) == 0) {
            throw BizException.of(400, "KB_DOMAIN_INVALID", "技能域不存在或未启用：" + domainId);
        }
    }

    private String nowText() {
        return LocalDateTime.now(ZoneOffset.UTC).format(SQLITE_DATETIME);
    }

    private String nowTextPlus(Period period) {
        return LocalDateTime.now(ZoneOffset.UTC).plus(period).format(SQLITE_DATETIME);
    }
}
