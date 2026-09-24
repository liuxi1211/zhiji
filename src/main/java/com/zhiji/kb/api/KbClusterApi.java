package com.zhiji.kb.api;

import java.util.List;

/**
 * 知识库簇能力的模块间契约（跨模块调用只走对方 api，repo 不出模块——CLAUDE.md 架构约束）。
 * 消费方：pipeline（语料簇是 draft 态 kp_cluster，归一引擎经此建簇 / 合簇 / 读域与知识点元数据）。
 */
public interface KbClusterApi {

    /** 建 draft 态簇（语料簇落点）；域不存在或未启用抛 KB_DOMAIN_INVALID。 */
    long createDraft(Long domainId, String name, String description);

    /** 簇合并（迁移知识点 + 源簇 retired 保留指向）。 */
    void merge(long sourceId, long targetId);

    /** 簇存在性校验（pipeline 侧归一题调簇 / 合簇前的逻辑引用校验，kp_canonical 无 FK）。 */
    boolean clusterExists(long clusterId);

    /** active 域清单（AI 命名建议的域约束）。 */
    List<DomainOption> listActiveDomains();

    /** active 知识点题面清单（归一题向已有知识点映射的比对集）。 */
    List<KpTitleRef> listActiveKpTitles();

    record DomainOption(long id, String name) {
    }

    record KpTitleRef(long id, String title) {
    }
}
