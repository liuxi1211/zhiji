package com.zhiji.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.kb.api.KbClusterApi;
import com.zhiji.kb.domain.ContentStatus;
import com.zhiji.kb.domain.KpMain;
import com.zhiji.kb.repo.ClusterMapper;
import com.zhiji.kb.repo.KpMainMapper;
import com.zhiji.kb.repo.KpMainMapper.MetaDomainRow;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class KbClusterApiImpl implements KbClusterApi {

    private final ClusterService clusterService;
    private final ClusterMapper clusterMapper;
    private final KpMainMapper kpMainMapper;

    public KbClusterApiImpl(ClusterService clusterService, ClusterMapper clusterMapper, KpMainMapper kpMainMapper) {
        this.clusterService = clusterService;
        this.clusterMapper = clusterMapper;
        this.kpMainMapper = kpMainMapper;
    }

    @Override
    public long createDraft(Long domainId, String name, String description) {
        return clusterService.create(domainId, name, description);
    }

    @Override
    public void merge(long sourceId, long targetId) {
        clusterService.merge(sourceId, targetId);
    }

    @Override
    public boolean clusterExists(long clusterId) {
        return clusterMapper.selectById(clusterId) != null;
    }

    @Override
    public List<DomainOption> listActiveDomains() {
        return kpMainMapper.selectMetaDomains().stream()
                .filter(row -> ContentStatus.ACTIVE.getCode().equals(row.getStatus()))
                .map(row -> new DomainOption(row.getId(), row.getName()))
                .toList();
    }

    @Override
    public List<KpTitleRef> listActiveKpTitles() {
        return kpMainMapper.selectList(new LambdaQueryWrapper<KpMain>()
                        .select(KpMain::getId, KpMain::getTitle)
                        .eq(KpMain::getStatus, ContentStatus.ACTIVE.getCode()))
                .stream().map(kp -> new KpTitleRef(kp.getId(), kp.getTitle()))
                .toList();
    }

    /** 域名存在性校验（AI 命名建议的域名→ID 映射用，避免 pipeline 绕道查 kb 表）。 */
    public boolean isDomainActive(long domainId) {
        return clusterMapper.countActiveDomain(domainId) > 0;
    }
}
