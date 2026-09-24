package com.zhiji.pipeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.api.KbClusterApi;
import com.zhiji.kb.api.KbClusterApi.DomainOption;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.pipeline.domain.CandidateStatus;
import com.zhiji.pipeline.domain.CanonicalStatus;
import com.zhiji.pipeline.domain.KpCandidate;
import com.zhiji.pipeline.domain.KpCanonical;
import com.zhiji.pipeline.domain.KpSourceDoc;
import com.zhiji.pipeline.repo.KpCandidateMapper;
import com.zhiji.pipeline.repo.KpCanonicalMapper;
import com.zhiji.pipeline.repo.KpCanonicalMapper.ClusterCoverageRow;
import com.zhiji.pipeline.repo.KpSourceDocMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 同题归一与知识地图编排（知识库PRD 九-3 / B端管理台PRD 第九节 / 决策清单 D21）。
 * 漏斗与聚类计算在 NormalizationFunnel（纯函数），本类负责取数、LLM 调用与落库。
 * 语料层是工作草稿：允许激进聚合、人工抽检兜底；built（映射到已有知识点，成稿产出随 T8）是唯一不可拆层。
 */
@Service
public class CanonicalService {

    private static final Logger log = LoggerFactory.getLogger(CanonicalService.class);

    /** embedding 单批文本数（provider 单请求上限保护）。 */
    private static final int EMBED_BATCH = 32;

    /** 簇名降级截断长度（LLM 命名失败时取代表题面前缀）。 */
    private static final int FALLBACK_NAME_LENGTH = 16;

    private static final String SYSTEM_JSON_ONLY = "你是面试题库整理助手，只输出 JSON。";

    private static final JsonMapper JSON = new JsonMapper();

    private final KpCandidateMapper candidateMapper;
    private final KpCanonicalMapper canonicalMapper;
    private final KpSourceDocMapper docMapper;
    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final KbClusterApi kbClusterApi;

    public CanonicalService(KpCandidateMapper candidateMapper, KpCanonicalMapper canonicalMapper,
            KpSourceDocMapper docMapper, LlmClient llmClient, PromptLoader promptLoader, KbClusterApi kbClusterApi) {
        this.candidateMapper = candidateMapper;
        this.canonicalMapper = canonicalMapper;
        this.docMapper = docMapper;
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
        this.kbClusterApi = kbClusterApi;
    }

    public record RunSummary(int looseCount, int newCanonicals, int absorbedItems, int mappedKp,
            int newClusters, boolean embeddingOk, int llmJudgeTruncated) {
    }

    /**
     * 增量归一（幂等）：只处理未吸收散题 + 未归簇归一题 + 未映射归一题，重复 run 不重建。
     * embedding / LLM 失败均降级不阻塞（结构信号兜底、中间带留给人工），整体一个事务（T6 SplitService 先例）。
     * 向量只活在本次调用的内存里，不落任何存储（PRD 九-3 字面口径）。
     */
    @Transactional
    public RunSummary run() {
        List<KpCandidate> loose = candidateMapper.selectUnabsorbedPending();
        List<KpCanonical> existing = canonicalMapper.selectList(null);
        List<KbClusterApi.KpTitleRef> activeKps = kbClusterApi.listActiveKpTitles();

        List<String> titles = new ArrayList<>();
        loose.forEach(c -> titles.add(c.getTitle()));
        existing.forEach(c -> titles.add(c.getTitle()));
        activeKps.forEach(kp -> titles.add(kp.title()));
        Map<String, float[]> embedded;
        boolean embeddingOk = true;
        try {
            embedded = embedTitles(titles);
        } catch (RuntimeException e) {
            log.warn("归一运行 embedding 失败，降级为纯结构信号：{}", e.getMessage());
            embedded = Map.of();
            embeddingOk = false;
        }
        Map<String, float[]> vectors = embedded;

        Map<Long, float[]> itemVectors = new HashMap<>();
        loose.forEach(c -> itemVectors.put(c.getId(), vectors.get(c.getTitle())));
        Map<Long, float[]> canonicalVectors = new HashMap<>();
        existing.forEach(c -> canonicalVectors.put(c.getId(), vectors.get(c.getTitle())));
        Map<Long, float[]> kpVectors = new HashMap<>();
        activeKps.forEach(kp -> kpVectors.put(kp.id(), vectors.get(kp.title())));

        NormalizationFunnel.GroupResult result = NormalizationFunnel.group(
                toItemViews(loose), toExisting(existing), itemVectors, canonicalVectors,
                new LlmJudge(loose));

        int newCanonicals = 0;
        int absorbedItems = 0;
        int mappedKp = 0;
        List<KpCanonical> unclustered = new ArrayList<>();
        for (NormalizationFunnel.Group group : result.groups()) {
            List<Long> memberIds = group.items().stream().map(NormalizationFunnel.Item::id).toList();
            if (group.canonicalId() != null) {
                KpCanonical target = require(group.canonicalId());
                attachMembers(memberIds, target.getId());
                target.setCoverageCount(coverageOf(target.getId()));
                canonicalMapper.updateById(target);
                absorbedItems += memberIds.size();
                continue;
            }
            KpCanonical canonical = new KpCanonical();
            canonical.setTitle(group.items().get(0).title());
            canonical.setStatus(group.status());
            canonical.setJudgeBasis(group.basis());
            canonical.setCoverageCount(countDistinctDocs(memberIds));
            applyKpMatch(canonical, canonical.getTitle(), vectors, kpVectors);
            if (CanonicalStatus.BUILT.getCode().equals(canonical.getStatus())) {
                mappedKp++;
            }
            canonicalMapper.insert(canonical);
            attachMembers(memberIds, canonical.getId());
            unclustered.add(canonical);
            newCanonicals++;
        }

        // 既有归一题补映射（先于知识点上线产生、后建的知识点吸收场景）
        for (KpCanonical canonical : existing) {
            if (canonical.getBuiltKpId() != null
                    || CanonicalStatus.BUILT.getCode().equals(canonical.getStatus())) {
                continue;
            }
            float[] vector = canonicalVectors.get(canonical.getId());
            if (vector == null) {
                continue;
            }
            Long kpId = bestKpMatch(vector, kpVectors);
            if (kpId != null) {
                canonical.setStatus(CanonicalStatus.BUILT.getCode());
                canonical.setBuiltKpId(kpId);
                canonicalMapper.updateById(canonical);
                mappedKp++;
            }
        }

        existing.forEach(c -> {
            if (c.getClusterId() == null) {
                unclustered.add(c);
            }
        });
        int newClusters = clusterAndName(unclustered, existing, vectors);

        return new RunSummary(loose.size(), newCanonicals, absorbedItems, mappedKp, newClusters,
                embeddingOk, Math.max(result.middlePairCount() - NormalizationFunnel.MAX_JUDGE_PAIRS, 0));
    }

    @Transactional
    public void confirm(long id, Long clusterId) {
        KpCanonical canonical = require(id);
        if (CanonicalStatus.BUILT.getCode().equals(canonical.getStatus())) {
            throw BizException.conflict("CANONICAL_IMMUTABLE", "已建设的归一题不可再改动，请先解除映射");
        }
        if (CanonicalStatus.CONFIRMED.getCode().equals(canonical.getStatus())) {
            throw BizException.of(400, "CANONICAL_ALREADY_CONFIRMED", "该归一题已确认入图");
        }
        canonical.setStatus(CanonicalStatus.CONFIRMED.getCode());
        if (clusterId != null) {
            requireCluster(clusterId);
            canonical.setClusterId(clusterId);
        }
        canonicalMapper.updateById(canonical);
    }

    /** 解除知识点映射（自动映射纠错口）：built 退回 confirmed，演化锚点清空。T8 成稿产出不得走此口（届时须加守卫）。 */
    @Transactional
    public void unbuild(long id) {
        KpCanonical canonical = require(id);
        if (!CanonicalStatus.BUILT.getCode().equals(canonical.getStatus())) {
            throw BizException.of(400, "CANONICAL_NOT_BUILT", "只有已建设的归一题可以解除映射");
        }
        // built_kp_id 要清成 null，必须用 UpdateWrapper 显式 set——updateById 忽略 null 字段
        canonicalMapper.update(null, new LambdaUpdateWrapper<KpCanonical>()
                .eq(KpCanonical::getId, id)
                .set(KpCanonical::getStatus, CanonicalStatus.CONFIRMED.getCode())
                .set(KpCanonical::getBuiltKpId, null));
    }

    /** 拆分回退（误归并兜底，B端管理台PRD 十四-2 验收硬指标）。all=整题解散；partial=拆出指定候选。 */
    @Transactional
    public Map<String, Object> split(long id, String mode, List<Long> candidateIds) {
        KpCanonical canonical = require(id);
        if (CanonicalStatus.BUILT.getCode().equals(canonical.getStatus())) {
            throw BizException.conflict("CANONICAL_IMMUTABLE", "已建设的归一题不可拆分，请先解除映射");
        }
        List<KpCandidate> members = candidateMapper.selectByCanonicalId(id);
        boolean dissolve;
        int returned;
        if ("all".equals(mode)) {
            dissolve = true;
            returned = members.size();
        } else if ("partial".equals(mode)) {
            if (candidateIds == null || candidateIds.isEmpty()) {
                throw BizException.validation("请选择要拆出的候选条目");
            }
            List<Long> memberIds = members.stream().map(KpCandidate::getId).toList();
            for (Long candidateId : candidateIds) {
                if (!memberIds.contains(candidateId)) {
                    throw BizException.validation("候选条目不属于该归一题：" + candidateId);
                }
            }
            returned = candidateIds.size();
            int remaining = members.size() - returned;
            dissolve = remaining == 0
                    || (remaining == 1 && CanonicalStatus.PENDING.getCode().equals(canonical.getStatus()));
        } else {
            throw BizException.validation("mode 只支持 all / partial");
        }
        List<Long> detachIds = "all".equals(mode)
                ? members.stream().map(KpCandidate::getId).toList()
                : candidateIds;
        detach(detachIds);
        if (dissolve && !"all".equals(mode)) {
            detach(candidateMapper.selectByCanonicalId(id).stream().map(KpCandidate::getId).toList());
        }
        if (dissolve) {
            canonicalMapper.deleteById(id);
        } else {
            canonical.setCoverageCount(coverageOf(id));
            canonicalMapper.updateById(canonical);
        }
        return Map.of("dissolved", dissolve, "returned", returned);
    }

    @Transactional
    public void moveCluster(long id, Long clusterId) {
        require(id);
        if (clusterId != null) {
            requireCluster(clusterId);
        }
        // clusterId 为空（移出簇）时必须显式 set null——updateById 忽略 null 字段
        canonicalMapper.update(null, new LambdaUpdateWrapper<KpCanonical>()
                .eq(KpCanonical::getId, id)
                .set(KpCanonical::getClusterId, clusterId));
    }

    /** 簇合并编排：先移归一题归属再 retire 源簇，同一事务防孤儿指向。 */
    @Transactional
    public void mergeClusters(long sourceId, long targetId) {
        if (sourceId == targetId) {
            throw BizException.of(400, "KB_MERGE_INVALID", "合并目标不能是簇自身");
        }
        requireCluster(sourceId);
        requireCluster(targetId);
        for (KpCanonical canonical : canonicalMapper.selectList(new LambdaQueryWrapper<KpCanonical>()
                .eq(KpCanonical::getClusterId, sourceId))) {
            canonical.setClusterId(targetId);
            canonicalMapper.updateById(canonical);
        }
        kbClusterApi.merge(sourceId, targetId);
    }

    public record CoverageCluster(long clusterId, String name, Long domainId, String domainName,
            long canonicalCount, long coverageSum, long builtCount) {
    }

    public record CoverageView(long domainCount, long clusterCount, long canonicalCount, long builtCount,
            long pendingCount, long unclusteredCount, List<CoverageCluster> clusters) {
    }

    /** 覆盖率统计（页头统计条 + 按簇建设排队依据，覆盖源合计倒序）。 */
    public CoverageView coverage() {
        List<ClusterCoverageRow> rows = canonicalMapper.selectClusterCoverages();
        long total = canonicalMapper.selectCount(null);
        long built = canonicalMapper.selectCount(new LambdaQueryWrapper<KpCanonical>()
                .eq(KpCanonical::getStatus, CanonicalStatus.BUILT.getCode()));
        long pending = canonicalMapper.selectCount(new LambdaQueryWrapper<KpCanonical>()
                .eq(KpCanonical::getStatus, CanonicalStatus.PENDING.getCode()));
        List<CoverageCluster> clusters = rows.stream()
                .map(r -> new CoverageCluster(r.getClusterId(), r.getClusterName(), r.getDomainId(),
                        r.getDomainName(), r.getCanonicalCount(), r.getCoverageSum(), r.getBuiltCount()))
                .toList();
        return new CoverageView(kbClusterApi.listActiveDomains().size(), clusters.size(), total, built,
                pending, canonicalMapper.countUnclustered(), clusters);
    }

    public record TreeDomain(long domainId, String domainName, long clusterCount, long canonicalCount,
            long coverageSum) {
    }

    public record TreeView(List<TreeDomain> domains, long unclusteredCount) {
    }

    /** 三层视图的域 → 簇层级（簇计数来自覆盖率聚合）。 */
    public TreeView tree() {
        Map<Long, TreeDomain> domains = new LinkedHashMap<>();
        for (DomainOption option : kbClusterApi.listActiveDomains()) {
            domains.put(option.id(), new TreeDomain(option.id(), option.name(), 0, 0, 0));
        }
        for (ClusterCoverageRow row : canonicalMapper.selectClusterCoverages()) {
            Long domainId = row.getDomainId() == null ? -1L : row.getDomainId();
            TreeDomain domain = domains.get(domainId);
            String domainName = domain != null ? domain.domainName()
                    : (row.getDomainName() == null ? "未归属域" : row.getDomainName());
            domains.put(domainId, new TreeDomain(domainId, domainName,
                    (domain == null ? 0 : domain.clusterCount()) + 1,
                    (domain == null ? 0 : domain.canonicalCount()) + row.getCanonicalCount(),
                    (domain == null ? 0 : domain.coverageSum()) + row.getCoverageSum()));
        }
        return new TreeView(new ArrayList<>(domains.values()), canonicalMapper.countUnclustered());
    }

    public record CanonicalItem(long id, String title, Long clusterId, int coverageCount, String status,
            String judgeBasis, Long builtKpId) {
    }

    /** 归一题列表（clusterId / unclustered / status 过滤，覆盖源数倒序）。 */
    public List<CanonicalItem> canonicals(Long clusterId, boolean unclustered, String status) {
        LambdaQueryWrapper<KpCanonical> wrapper = new LambdaQueryWrapper<>();
        if (unclustered) {
            wrapper.isNull(KpCanonical::getClusterId);
        } else if (clusterId != null) {
            wrapper.eq(KpCanonical::getClusterId, clusterId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(KpCanonical::getStatus, CanonicalStatus.fromCode(status).getCode());
        }
        wrapper.orderByDesc(KpCanonical::getCoverageCount).orderByAsc(KpCanonical::getId);
        return canonicalMapper.selectList(wrapper).stream().map(CanonicalService::toItem).toList();
    }

    public record SourceItem(long candidateId, long docId, String docFileName, String title,
            String answerEvidence, String sectionPath, String form, String sourceRef) {
    }

    public record CanonicalDetail(CanonicalItem canonical, List<SourceItem> sources) {
    }

    /** 归一题详情 + 源候选溯源（回跳素材原文的 docId 由前端拼 /admin/docs?docId=）。 */
    public CanonicalDetail detail(long id) {
        KpCanonical canonical = require(id);
        List<KpCandidate> members = candidateMapper.selectByCanonicalId(id);
        Map<Long, KpSourceDoc> docs = new LinkedHashMap<>();
        if (!members.isEmpty()) {
            docMapper.selectBatchIds(members.stream().map(KpCandidate::getDocId).distinct().toList())
                    .forEach(doc -> docs.put(doc.getId(), doc));
        }
        List<SourceItem> sources = members.stream()
                .map(candidate -> {
                    KpSourceDoc doc = docs.get(candidate.getDocId());
                    return new SourceItem(candidate.getId(), candidate.getDocId(),
                            doc == null ? null : doc.getFileName(), candidate.getTitle(),
                            candidate.getAnswerEvidence(), candidate.getSectionPath(), candidate.getForm(),
                            doc == null ? null : doc.getSourceRef());
                })
                .toList();
        return new CanonicalDetail(toItem(canonical), sources);
    }

    public record NameSuggestion(String name, Long domainId, String domainName) {
    }

    /** AI 簇命名建议（只建议不落库，人工采纳走既有簇更新接口）。 */
    public NameSuggestion suggestName(long clusterId) {
        List<KpCanonical> members = canonicalMapper.selectList(new LambdaQueryWrapper<KpCanonical>()
                .eq(KpCanonical::getClusterId, clusterId));
        if (members.isEmpty()) {
            throw BizException.validation("该簇下没有归一题，无法生成命名建议");
        }
        List<DomainOption> domains = kbClusterApi.listActiveDomains();
        String segment = dominantSegment(clusterId);
        Map<String, Object> parsed = callLlmJson("cluster_name", Map.of(
                "domains", JSON.writeValueAsString(domains.stream().map(DomainOption::name).toList()),
                "clusters", JSON.writeValueAsString(List.of(Map.of(
                        "index", 0,
                        "samples", members.stream().limit(3).map(KpCanonical::getTitle).toList(),
                        "section", segment)))));
        List<Map<String, Object>> names = castList(parsed.get("names"));
        if (names == null || names.isEmpty()) {
            throw BizException.of(502, "SYS_PROVIDER_ERROR", "模型未返回命名建议，请重试");
        }
        String name = textOf(names.get(0).get("name"));
        String domain = textOf(names.get(0).get("domain"));
        Long domainId = matchDomain(domain, domains, segment);
        return new NameSuggestion(name.isBlank() ? fallbackName(members.get(0).getTitle()) : name,
                domainId, domain);
    }

    /* ================= 归一 run 的内部实现 ================= */

    /** 语料簇聚类 + AI 命名（增量）：先挂既有簇，剩余聚新簇；返回新建簇数。 */
    private int clusterAndName(List<KpCanonical> unclustered, List<KpCanonical> all, Map<String, float[]> vectors) {
        if (unclustered.isEmpty()) {
            return 0;
        }
        List<DomainOption> domains = kbClusterApi.listActiveDomains();
        if (domains.isEmpty()) {
            log.warn("无 active 技能域，跳过语料簇聚类（归一题保持未归簇）");
            return 0;
        }
        Map<Long, List<float[]>> existingClusterVectors = new HashMap<>();
        Set<Long> unclusteredIds = new HashSet<>();
        unclustered.forEach(c -> unclusteredIds.add(c.getId()));
        for (KpCanonical canonical : all) {
            if (canonical.getClusterId() != null && !unclusteredIds.contains(canonical.getId())) {
                float[] vector = vectors.get(canonical.getTitle());
                if (vector != null) {
                    existingClusterVectors.computeIfAbsent(canonical.getClusterId(), k -> new ArrayList<>())
                            .add(vector);
                }
            }
        }
        List<NormalizationFunnel.ClusterNode> nodes = unclustered.stream()
                .map(c -> new NormalizationFunnel.ClusterNode(c.getId(), c.getTitle(), vectors.get(c.getTitle()),
                        dominantSegment(c.getId())))
                .toList();
        NormalizationFunnel.ClusterOutcome outcome = NormalizationFunnel.assignClusters(nodes, existingClusterVectors);

        outcome.attached().forEach((canonicalId, clusterId) -> {
            KpCanonical canonical = require(canonicalId);
            canonical.setClusterId(clusterId);
            canonicalMapper.updateById(canonical);
        });

        List<List<KpCanonical>> newGroups = new ArrayList<>();
        for (List<Long> group : outcome.newGroups()) {
            newGroups.add(group.stream().map(this::require).toList());
        }
        List<Map<String, Object>> namingPayload = new ArrayList<>();
        for (int i = 0; i < newGroups.size(); i++) {
            List<KpCanonical> members = newGroups.get(i);
            namingPayload.add(Map.of(
                    "index", i,
                    "samples", members.stream().limit(3).map(KpCanonical::getTitle).toList(),
                    "section", dominantSegment(members.get(0).getId())));
        }
        Map<Integer, NameSuggestion> suggestions = suggestNames(namingPayload, domains);

        int created = 0;
        for (int i = 0; i < newGroups.size(); i++) {
            List<KpCanonical> members = newGroups.get(i);
            NameSuggestion suggestion = suggestions.get(i);
            String name = suggestion != null ? suggestion.name()
                    : fallbackName(members.get(0).getTitle());
            Long domainId = suggestion != null && suggestion.domainId() != null
                    ? suggestion.domainId()
                    : domains.get(0).id();
            long clusterId = kbClusterApi.createDraft(domainId, name, null);
            created++;
            for (KpCanonical member : members) {
                member.setClusterId(clusterId);
                canonicalMapper.updateById(member);
            }
        }
        return created;
    }

    /** 批量簇命名建议；LLM 失败降级为空表（用降级簇名，不阻塞建簇）。 */
    private Map<Integer, NameSuggestion> suggestNames(List<Map<String, Object>> payload, List<DomainOption> domains) {
        if (payload.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = callLlmJson("cluster_name", Map.of(
                    "domains", JSON.writeValueAsString(domains.stream().map(DomainOption::name).toList()),
                    "clusters", JSON.writeValueAsString(payload)));
            List<Map<String, Object>> names = castList(parsed.get("names"));
            Map<Integer, NameSuggestion> result = new HashMap<>();
            if (names == null) {
                return result;
            }
            for (Map<String, Object> item : names) {
                int index = (int) numberOf(item.get("index"));
                String name = textOf(item.get("name"));
                String domain = textOf(item.get("domain"));
                if (index >= 0 && index < payload.size()) {
                    result.put(index, new NameSuggestion(name.isBlank() ? null : name,
                            matchDomain(domain, domains, textOf(payload.get(index).get("section"))), domain));
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("AI 簇命名建议失败，使用降级簇名：{}", e.getMessage());
            return Map.of();
        }
    }

    /** 中间带 LLM 精判回调：批量判对，失败按「不同题」保守处理（留在候选池人工研判）。 */
    private class LlmJudge implements NormalizationFunnel.Judge {

        private final Map<Long, KpCandidate> looseById;

        LlmJudge(List<KpCandidate> loose) {
            this.looseById = new HashMap<>();
            loose.forEach(c -> looseById.put(c.getId(), c));
        }

        @Override
        public boolean[] judgePairs(List<Pair> pairs) {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) {
                Pair pair = pairs.get(i);
                payload.add(Map.of(
                        "index", i,
                        "titleA", pair.a().title(),
                        "evidenceA", evidenceOf(pair.a().id()),
                        "titleB", pair.b().title(),
                        "evidenceB", evidenceOf(pair.b().id())));
            }
            return judgeBatch(payload, pairs.size());
        }

        @Override
        public boolean[] judgeCanonPairs(List<CanonPair> pairs) {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) {
                CanonPair pair = pairs.get(i);
                payload.add(Map.of(
                        "index", i,
                        "titleA", pair.item().title(),
                        "evidenceA", evidenceOf(pair.item().id()),
                        "titleB", pair.canonical().title(),
                        "evidenceB", ""));
            }
            return judgeBatch(payload, pairs.size());
        }

        private String evidenceOf(long candidateId) {
            KpCandidate candidate = looseById.get(candidateId);
            return candidate == null || candidate.getAnswerEvidence() == null
                    ? "" : candidate.getAnswerEvidence();
        }

        private boolean[] judgeBatch(List<Map<String, Object>> payload, int size) {
            boolean[] result = new boolean[size];
            try {
                Map<String, Object> parsed = callLlmJson("canonical_judge",
                        Map.of("pairs", JSON.writeValueAsString(payload)));
                List<Map<String, Object>> judgments = castList(parsed.get("judgments"));
                if (judgments == null) {
                    return result;
                }
                for (Map<String, Object> judgment : judgments) {
                    int index = (int) numberOf(judgment.get("index"));
                    if (index >= 0 && index < size) {
                        result[index] = Boolean.TRUE.equals(judgment.get("same"));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("LLM 同题精判失败，中间带按不同题处理：{}", e.getMessage());
            }
            return result;
        }
    }

    private Map<String, Object> callLlmJson(String promptName, Map<String, ?> vars) {
        String raw = llmClient.complete(LlmRequest.of(LlmPurpose.CLUSTER_DEDUP, 0L, SYSTEM_JSON_ONLY,
                promptLoader.render(promptName, vars)));
        return ModelJson.parse(raw, new TypeReference<Map<String, Object>>() {
        });
    }

    /** 题面向量化：按标题去重 + 分批（向量只活在本次 run 内存，不落任何存储）。 */
    private Map<String, float[]> embedTitles(Collection<String> titles) {
        List<String> unique = titles.stream()
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .toList();
        Map<String, float[]> result = new HashMap<>();
        for (int i = 0; i < unique.size(); i += EMBED_BATCH) {
            List<String> chunk = unique.subList(i, Math.min(unique.size(), i + EMBED_BATCH));
            List<float[]> vectors = llmClient.embedAll(LlmPurpose.EMBEDDING, 0L, chunk);
            for (int k = 0; k < chunk.size(); k++) {
                result.put(chunk.get(k), vectors.get(k));
            }
        }
        return result;
    }

    private void applyKpMatch(KpCanonical canonical, String title, Map<String, float[]> vectors,
            Map<Long, float[]> kpVectors) {
        float[] vector = vectors.get(title);
        if (vector == null) {
            return;
        }
        Long kpId = bestKpMatch(vector, kpVectors);
        if (kpId != null) {
            canonical.setStatus(CanonicalStatus.BUILT.getCode());
            canonical.setBuiltKpId(kpId);
        }
    }

    private Long bestKpMatch(float[] vector, Map<Long, float[]> kpVectors) {
        Long best = null;
        double bestSim = -1;
        for (Map.Entry<Long, float[]> entry : kpVectors.entrySet()) {
            double sim = NormalizationFunnel.cosine(vector, entry.getValue());
            if (sim > bestSim) {
                bestSim = sim;
                best = entry.getKey();
            }
        }
        return bestSim >= NormalizationFunnel.EXACT_SIMILARITY ? best : null;
    }

    /** 域名→ID 映射：先精确名，再章节首段包含匹配，最后落第一个 active 域。 */
    private Long matchDomain(String domainName, List<DomainOption> domains, String segment) {
        for (DomainOption option : domains) {
            if (option.name().equalsIgnoreCase(domainName)) {
                return option.id();
            }
        }
        String normSegment = NormalizationFunnel.normalizeTitle(segment);
        if (!normSegment.isEmpty()) {
            for (DomainOption option : domains) {
                String normDomain = NormalizationFunnel.normalizeTitle(option.name());
                if (normDomain.contains(normSegment) || normSegment.contains(normDomain)) {
                    return option.id();
                }
            }
        }
        return domains.isEmpty() ? null : domains.get(0).id();
    }

    /** 归一题主章节首段（成员候选 section_path 首段众数）。 */
    private String dominantSegment(long canonicalId) {
        Map<String, Integer> counts = new HashMap<>();
        for (KpCandidate candidate : candidateMapper.selectByCanonicalId(canonicalId)) {
            String segment = NormalizationFunnel.firstSegment(candidate.getSectionPath());
            if (!segment.isEmpty()) {
                counts.merge(segment, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private void attachMembers(List<Long> memberIds, long canonicalId) {
        for (Long memberId : memberIds) {
            KpCandidate candidate = candidateMapper.selectById(memberId);
            if (candidate == null) {
                continue;
            }
            candidate.setCanonicalId(canonicalId);
            candidateMapper.updateById(candidate);
        }
    }

    private void detach(List<Long> memberIds) {
        for (Long memberId : memberIds) {
            KpCandidate candidate = candidateMapper.selectById(memberId);
            if (candidate == null) {
                continue;
            }
            // canonical_id 要清成 null，必须用 UpdateWrapper 显式 set——updateById 忽略 null 字段
            candidateMapper.update(null, new LambdaUpdateWrapper<KpCandidate>()
                    .eq(KpCandidate::getId, memberId)
                    .set(KpCandidate::getCanonicalId, null)
                    .set(KpCandidate::getStatus, CandidateStatus.PENDING.getCode()));
        }
    }

    private int coverageOf(long canonicalId) {
        Set<Long> docIds = new HashSet<>();
        for (KpCandidate candidate : candidateMapper.selectByCanonicalId(canonicalId)) {
            docIds.add(candidate.getDocId());
        }
        return docIds.size();
    }

    private int countDistinctDocs(List<Long> memberIds) {
        Set<Long> docIds = new HashSet<>();
        for (Long memberId : memberIds) {
            KpCandidate candidate = candidateMapper.selectById(memberId);
            if (candidate != null) {
                docIds.add(candidate.getDocId());
            }
        }
        return docIds.size();
    }

    private KpCanonical require(long id) {
        KpCanonical canonical = canonicalMapper.selectById(id);
        if (canonical == null) {
            throw BizException.notFound("归一题不存在：" + id);
        }
        return canonical;
    }

    private void requireCluster(long clusterId) {
        if (!kbClusterApi.clusterExists(clusterId)) {
            throw BizException.of(400, "KB_CLUSTER_NOT_FOUND", "主题簇不存在：" + clusterId);
        }
    }

    private String fallbackName(String title) {
        if (title == null) {
            return "未命名簇";
        }
        String stripped = title.strip();
        return stripped.length() <= FALLBACK_NAME_LENGTH ? stripped
                : stripped.substring(0, FALLBACK_NAME_LENGTH);
    }

    private List<NormalizationFunnel.Item> toItemViews(List<KpCandidate> loose) {
        return loose.stream()
                .map(c -> new NormalizationFunnel.Item(c.getId(), c.getDocId(), c.getTitle(), c.getSectionPath()))
                .toList();
    }

    private List<NormalizationFunnel.Existing> toExisting(List<KpCanonical> existing) {
        return existing.stream()
                .map(c -> new NormalizationFunnel.Existing(c.getId(), c.getTitle()))
                .toList();
    }

    private static CanonicalItem toItem(KpCanonical canonical) {
        return new CanonicalItem(canonical.getId(), canonical.getTitle(), canonical.getClusterId(),
                canonical.getCoverageCount() == null ? 0 : canonical.getCoverageCount(),
                canonical.getStatus(), canonical.getJudgeBasis(), canonical.getBuiltKpId());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : null;
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static double numberOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : -1;
    }
}
