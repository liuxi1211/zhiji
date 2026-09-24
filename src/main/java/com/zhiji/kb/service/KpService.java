package com.zhiji.kb.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.ContentStatus;
import com.zhiji.kb.domain.KpCluster;
import com.zhiji.kb.domain.KpFreqSignal;
import com.zhiji.kb.domain.KpMain;
import com.zhiji.kb.domain.KpPrereq;
import com.zhiji.kb.domain.LevelTarget;
import com.zhiji.kb.repo.ClusterMapper;
import com.zhiji.kb.repo.KpFreqSignalMapper;
import com.zhiji.kb.repo.KpMainMapper;
import com.zhiji.kb.repo.KpMainMapper.KpListRow;
import com.zhiji.kb.repo.KpMainMapper.MetaClusterRow;
import com.zhiji.kb.repo.KpMainMapper.MetaDomainRow;
import com.zhiji.kb.repo.KpPrereqMapper;
import com.zhiji.kb.repo.KpPrereqMapper.PrereqItemRow;
import com.zhiji.kb.service.KpStructureValidator.FollowupLayer;
import com.zhiji.kb.service.KpStructureValidator.KpSavePayload;
import com.zhiji.kb.service.KpStructureValidator.LevelReq;
import com.zhiji.kb.service.KpStructureValidator.RubricGroup;
import com.zhiji.kb.service.KpStructureValidator.RubricPoint;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 知识点全功能服务：结构 CRUD、追问层身份管理、状态机、下线（弃用/合并）、频率信号、检索与元数据。
 * 写操作全部 @Transactional（事务边界在这一层）。
 *
 * 关键口径：
 * - pointId 是评分要点的稳定身份：库中已有 pointId 保留原值，空白才分配 "p"+8位随机hex。
 * - baseline_levels 由 levelReq 确定性重算：stopLayer 非空的档位即视为已标。
 * - layer_key 是追问层稳定身份，update 不允许增删层（必须走专属接口），防止历史判定错位。
 */
@Service
public class KpService {

    /**
     * 复核周期 90 天。写库时间用 UTC——schema 里 datetime('now') 默认值就是 UTC，
     * Java 侧同样取 UTC 再格式化，两边口径一致（与 ClusterService 同源）。
     */
    private static final Period REVIEW_PERIOD = Period.ofDays(90);

    private static final DateTimeFormatter SQLITE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final JsonMapper JSON = new JsonMapper();

    private static final Pattern LAYER_KEY_PATTERN = Pattern.compile("^L(\\d+)$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KpMainMapper kpMainMapper;
    private final ClusterMapper clusterMapper;
    private final KpPrereqMapper kpPrereqMapper;
    private final KpFreqSignalMapper kpFreqSignalMapper;

    public KpService(KpMainMapper kpMainMapper, ClusterMapper clusterMapper,
            KpPrereqMapper kpPrereqMapper, KpFreqSignalMapper kpFreqSignalMapper) {
        this.kpMainMapper = kpMainMapper;
        this.clusterMapper = clusterMapper;
        this.kpPrereqMapper = kpPrereqMapper;
        this.kpFreqSignalMapper = kpFreqSignalMapper;
    }

    public record KpListItem(long id, String title, long domainId, String domainName, long clusterId,
            String clusterName, String subType, int difficulty, String status, String baselineLevels,
            String lastReviewedAt, String reviewDueAt) {
    }

    public record PrereqItem(long id, String title) {
    }

    public record FreqSignalView(long id, String source, String signalLevel, int signalCount,
            String note, String createdAt) {
    }

    public record KpDetailView(long id, long clusterId, String clusterName, long domainId,
            String domainName, String subType, String title, List<String> variants,
            String narrativeFramework, List<FollowupLayer> followups, List<RubricGroup> rubric,
            Map<String, String> samples, Map<String, LevelReq> levelReq, int difficulty, String status,
            Long mergedInto, String lastReviewedAt, String reviewDueAt, List<Long> prereqIds,
            List<PrereqItem> prereqList, List<FreqSignalView> freqSignals) {
    }

    public record DomainMeta(long id, String name, String status, long kpCount) {
    }

    public record ClusterMeta(long id, String name, long domainId, String status) {
    }

    public record KpMetaView(List<DomainMeta> domains, List<ClusterMeta> clusters) {
    }

    @Transactional
    public long create(KpSavePayload payload) {
        requireClusterForKp(payload.clusterId());
        KpStructureValidator.validate(payload);
        List<RubricGroup> rubric = assignPointIds(payload.rubric(), new HashSet<>());
        KpMain kp = new KpMain();
        kp.setClusterId(payload.clusterId());
        kp.setSubType(payload.subType());
        kp.setTitle(payload.title().trim());
        kp.setVariants(writeVariants(payload.variants()));
        kp.setNarrativeFramework(payload.narrativeFramework());
        kp.setFollowups(writeFollowups(payload.followups()));
        kp.setRubric(JSON.writeValueAsString(rubric));
        kp.setSamples(writeJsonMap(payload.samples()));
        kp.setLevelReq(writeJsonMap(payload.levelReq()));
        kp.setBaselineLevels(computeBaselineLevels(payload.levelReq()));
        kp.setDifficulty(payload.difficulty());
        kp.setStatus(ContentStatus.DRAFT.getCode());
        kpMainMapper.insert(kp);
        replacePrereqs(kp.getId(), payload.prereqIds());
        return kp.getId();
    }

    @Transactional
    public void update(long id, KpSavePayload payload) {
        KpMain kp = requireKp(id);
        Set<String> dbLayerKeys = readFollowups(kp.getFollowups()).stream()
                .map(FollowupLayer::layerKey).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> payloadLayerKeys = new LinkedHashSet<>();
        if (payload.followups() != null) {
            payload.followups().forEach(l -> {
                if (l != null && l.layerKey() != null) {
                    payloadLayerKeys.add(l.layerKey());
                }
            });
        }
        if (!dbLayerKeys.equals(payloadLayerKeys)) {
            throw BizException.of(400, "KB_LAYER_KEY_UNKNOWN",
                    "存在未知追问层身份，新增/停用层必须走对应接口");
        }
        Set<String> dbPointIds = collectPointIds(readRubric(kp.getRubric()));
        if (payload.rubric() != null) {
            for (RubricGroup group : payload.rubric()) {
                if (group == null || group.points() == null) {
                    continue;
                }
                for (RubricPoint point : group.points()) {
                    if (point != null && point.pointId() != null && !point.pointId().isBlank()
                            && !dbPointIds.contains(point.pointId())) {
                        throw BizException.of(400, "KB_POINT_ID_UNKNOWN",
                                "存在未知评分要点身份：" + point.pointId());
                    }
                }
            }
        }
        requireClusterForKp(payload.clusterId());
        KpStructureValidator.validate(payload);
        List<RubricGroup> rubric = assignPointIds(payload.rubric(), new HashSet<>(dbPointIds));
        kp.setClusterId(payload.clusterId());
        kp.setSubType(payload.subType());
        kp.setTitle(payload.title().trim());
        kp.setVariants(writeVariants(payload.variants()));
        kp.setNarrativeFramework(payload.narrativeFramework());
        kp.setFollowups(writeFollowups(payload.followups()));
        kp.setRubric(JSON.writeValueAsString(rubric));
        kp.setSamples(writeJsonMap(payload.samples()));
        kp.setLevelReq(writeJsonMap(payload.levelReq()));
        kp.setBaselineLevels(computeBaselineLevels(payload.levelReq()));
        kp.setDifficulty(payload.difficulty());
        if (ContentStatus.ACTIVE.getCode().equals(kp.getStatus())) {
            String now = nowText();
            kp.setLastReviewedAt(now);
            kp.setReviewDueAt(nowTextPlus(REVIEW_PERIOD));
        }
        kpMainMapper.updateById(kp);
        replacePrereqs(id, payload.prereqIds());
    }

    @Transactional
    public String addFollowupLayer(long id, String dir, String text, String expect, String stopCondition) {
        KpMain kp = requireKp(id);
        List<FollowupLayer> layers = new ArrayList<>(readFollowups(kp.getFollowups()));
        int max = 0;
        for (FollowupLayer layer : layers) {
            if (layer.layerKey() == null) {
                continue;
            }
            Matcher matcher = LAYER_KEY_PATTERN.matcher(layer.layerKey());
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        String layerKey = "L" + (max + 1);
        layers.add(new FollowupLayer(layerKey, dir, text, expect, stopCondition, "active"));
        KpStructureValidator.validate(new KpSavePayload(kp.getClusterId(), kp.getSubType(), kp.getTitle(),
                kp.getDifficulty(), readVariants(kp.getVariants()), kp.getNarrativeFramework(), layers,
                readRubric(kp.getRubric()), readSamples(kp.getSamples()), readLevelReq(kp.getLevelReq()),
                null));
        kp.setFollowups(JSON.writeValueAsString(layers));
        kpMainMapper.updateById(kp);
        return layerKey;
    }

    @Transactional
    public void disableLayer(long id, String layerKey) {
        KpMain kp = requireKp(id);
        if ("main".equals(layerKey)) {
            throw BizException.of(400, "KB_LAYER_KEY_INVALID", "主问题层身份固定为 main，不通过该接口停用");
        }
        List<FollowupLayer> layers = readFollowups(kp.getFollowups());
        for (int i = 0; i < layers.size(); i++) {
            FollowupLayer layer = layers.get(i);
            if (!layerKey.equals(layer.layerKey())) {
                continue;
            }
            if ("disabled".equals(layer.status())) {
                throw BizException.of(400, "KB_LAYER_KEY_INVALID", "追问层已停用：" + layerKey);
            }
            layers.set(i, new FollowupLayer(layer.layerKey(), layer.dir(), layer.text(), layer.expect(),
                    layer.stopCondition(), "disabled"));
            kp.setFollowups(JSON.writeValueAsString(layers));
            kpMainMapper.updateById(kp);
            return;
        }
        throw BizException.of(400, "KB_LAYER_KEY_INVALID", "追问层不存在：" + layerKey);
    }

    @Transactional
    public void changeStatus(long id, String targetStatus) {
        KpMain kp = requireKp(id);
        ContentStatus current = ContentStatus.fromCode(kp.getStatus());
        ContentStatus target = ContentStatus.fromCode(targetStatus);
        if (target == ContentStatus.REVIEWED && current != ContentStatus.DRAFT
                || target == ContentStatus.ACTIVE && current != ContentStatus.REVIEWED) {
            throw BizException.of(400, "KB_STATUS_ILLEGAL", "状态只允许 draft→reviewed→active 逐步推进，当前："
                    + kp.getStatus() + "，目标：" + targetStatus);
        }
        String now = nowText();
        kp.setLastReviewedAt(now);
        kp.setReviewDueAt(nowTextPlus(REVIEW_PERIOD));
        kp.setStatus(target.getCode());
        kpMainMapper.updateById(kp);
    }

    @Transactional
    public void retire(long id, String mode, Long targetId) {
        KpMain kp = requireKp(id);
        checkPersonalRefs(id);
        if (ContentStatus.RETIRED.getCode().equals(kp.getStatus())) {
            throw BizException.of(400, "KB_STATUS_ILLEGAL", "已下线的知识点不能再流转");
        }
        if ("discard".equals(mode)) {
            kp.setStatus(ContentStatus.RETIRED.getCode());
            kp.setMergedInto(null);
            kpMainMapper.updateById(kp);
            return;
        }
        if (!"merge".equals(mode)) {
            throw BizException.of(400, "KB_MERGE_INVALID", "下线方式只允许 discard/merge：" + mode);
        }
        if (targetId == null) {
            throw BizException.of(400, "KB_MERGE_INVALID", "合并目标不能为空");
        }
        if (targetId == id) {
            throw BizException.of(400, "KB_MERGE_INVALID", "合并目标不能是知识点自身");
        }
        KpMain target = kpMainMapper.selectById(targetId);
        if (target == null) {
            throw BizException.of(400, "KB_MERGE_INVALID", "合并目标不存在：" + targetId);
        }
        if (ContentStatus.RETIRED.getCode().equals(target.getStatus())) {
            throw BizException.of(400, "KB_MERGE_INVALID", "合并目标已下线");
        }
        List<String> merged = new ArrayList<>(readVariants(target.getVariants()));
        Set<String> seen = new LinkedHashSet<>(merged);
        for (String variant : readVariants(kp.getVariants())) {
            if (seen.add(variant)) {
                merged.add(variant);
            }
        }
        target.setVariants(JSON.writeValueAsString(merged));
        kpMainMapper.updateById(target);
        kp.setStatus(ContentStatus.RETIRED.getCode());
        kp.setMergedInto(targetId);
        kpMainMapper.updateById(kp);
    }

    /**
     * 个人态引用检查：下线前确认没有个人路径/进度引用本知识点。
     * user_* 表随 T10+（C 端学习链路）接入，T4 阶段恒为空；
     * 留在此处保证 user_* 接入后合并/弃用逻辑不漏检查。
     */
    private void checkPersonalRefs(long kpId) {
    }

    @Transactional
    public void saveFreqSignal(long id, String signalLevel, String note) {
        requireKp(id);
        KpFreqSignal existing = kpFreqSignalMapper.selectManual(id);
        if (existing == null) {
            KpFreqSignal signal = new KpFreqSignal();
            signal.setKpId(id);
            signal.setSource("manual");
            signal.setSignalLevel(signalLevel);
            signal.setSignalCount(1);
            signal.setNote(note);
            kpFreqSignalMapper.insert(signal);
        } else {
            kpFreqSignalMapper.updateManual(existing.getId(), signalLevel, note);
        }
    }

    public Map<String, Object> list(String keyword, Long domainId, Long clusterId, String subType,
            String status, Integer difficulty, String baseline, Boolean reviewDue, int page, int size) {
        String kw = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        int offset = Math.max(0, (page - 1) * size);
        List<KpListItem> items = kpMainMapper
                .selectKpList(kw, domainId, clusterId, subType, status, difficulty, baseline, reviewDue,
                        size, offset)
                .stream().map(this::toListItem).toList();
        long total = kpMainMapper.countKpList(kw, domainId, clusterId, subType, status, difficulty,
                baseline, reviewDue);
        return Map.of("list", items, "total", total);
    }

    public KpDetailView detail(long id) {
        KpMain kp = requireKp(id);
        KpCluster cluster = clusterMapper.selectById(kp.getClusterId());
        String domainName = cluster == null ? null
                : clusterMapper.selectDomainName(cluster.getDomainId());
        List<PrereqItem> prereqList = kpPrereqMapper.selectPrereqItems(id).stream()
                .map(row -> new PrereqItem(row.getId(), row.getTitle())).toList();
        List<FreqSignalView> freqSignals = kpFreqSignalMapper.selectByKpId(id).stream()
                .map(s -> new FreqSignalView(s.getId(), s.getSource(), s.getSignalLevel(),
                        s.getSignalCount() == null ? 0 : s.getSignalCount(), s.getNote(), s.getCreatedAt()))
                .toList();
        return new KpDetailView(kp.getId(), kp.getClusterId(),
                cluster == null ? null : cluster.getName(),
                cluster == null ? 0 : cluster.getDomainId(), domainName, kp.getSubType(), kp.getTitle(),
                readVariants(kp.getVariants()), kp.getNarrativeFramework(),
                readFollowups(kp.getFollowups()), readRubric(kp.getRubric()),
                readSamples(kp.getSamples()), readLevelReq(kp.getLevelReq()), kp.getDifficulty(),
                kp.getStatus(), kp.getMergedInto(), kp.getLastReviewedAt(), kp.getReviewDueAt(),
                kpPrereqMapper.selectByKpId(id).stream().map(KpPrereq::getPrereqKpId).toList(),
                prereqList, freqSignals);
    }

    public KpMetaView meta() {
        List<DomainMeta> domains = kpMainMapper.selectMetaDomains().stream()
                .map(d -> new DomainMeta(d.getId(), d.getName(), d.getStatus(),
                        d.getKpCount() == null ? 0 : d.getKpCount()))
                .toList();
        List<ClusterMeta> clusters = kpMainMapper.selectMetaClusters().stream()
                .map(c -> new ClusterMeta(c.getId(), c.getName(), c.getDomainId(), c.getStatus()))
                .toList();
        return new KpMetaView(domains, clusters);
    }

    /**
     * 知识卡分享公开视图（知识库PRD 7.5）：仅输出 active 知识点的库侧内容。
     * 红线：不含 levelReq / rubric（目标刻度依赖个人职级不渲染，评分要点是判定尺子不外露），
     * 不含任何个人数据；非 active 统一 404 不泄露存在性。
     */
    public ShareKpView shareDetail(long id) {
        KpMain kp = requireKp(id);
        if (!ContentStatus.ACTIVE.getCode().equals(kp.getStatus())) {
            throw BizException.notFound("知识点不存在或已下线");
        }
        KpCluster cluster = kp.getClusterId() == null ? null : clusterMapper.selectById(kp.getClusterId());
        String domainName = cluster == null ? null
                : clusterMapper.selectDomainName(cluster.getDomainId());
        List<FollowupLayer> followups = readFollowups(kp.getFollowups()).stream()
                .filter(l -> !"disabled".equals(l.status()))
                .toList();
        return new ShareKpView(kp.getId(), kp.getTitle(), kp.getSubType(), kp.getDifficulty(),
                readVariants(kp.getVariants()), kp.getNarrativeFramework(), followups,
                readSamples(kp.getSamples()), domainName,
                cluster == null ? null : cluster.getName(),
                cluster == null ? null : cluster.getZeroShotMd(),
                cluster == null ? null : cluster.getReferenceSolutionMd());
    }

    public record ShareKpView(long id, String title, String subType, int difficulty,
            List<String> variants, String narrativeFramework, List<FollowupLayer> followups,
            Map<String, String> samples, String domainName, String clusterName,
            String zeroShotMd, String referenceSolutionMd) {
    }

    private KpListItem toListItem(KpListRow row) {
        return new KpListItem(row.getId(), row.getTitle(), row.getDomainId(), row.getDomainName(),
                row.getClusterId(), row.getClusterName(), row.getSubType(), row.getDifficulty(),
                row.getStatus(), row.getBaselineLevels(), row.getLastReviewedAt(), row.getReviewDueAt());
    }

    private KpMain requireKp(long id) {
        KpMain kp = kpMainMapper.selectById(id);
        if (kp == null) {
            throw BizException.notFound("知识点不存在或已删除：" + id);
        }
        return kp;
    }

    private void requireClusterForKp(Long clusterId) {
        if (clusterId == null) {
            throw BizException.of(400, "KB_CLUSTER_INVALID", "所属簇不能为空");
        }
        KpCluster cluster = clusterMapper.selectById(clusterId);
        if (cluster == null) {
            throw BizException.of(400, "KB_CLUSTER_INVALID", "所属簇不存在：" + clusterId);
        }
        if (ContentStatus.RETIRED.getCode().equals(cluster.getStatus())) {
            throw BizException.of(400, "KB_CLUSTER_INVALID", "所属簇已下线：" + cluster.getName());
        }
    }

    private void replacePrereqs(long kpId, List<Long> prereqIds) {
        List<Long> ids = prereqIds == null ? List.of()
                : prereqIds.stream().filter(Objects::nonNull).distinct().toList();
        for (Long prereqId : ids) {
            KpMain target = kpMainMapper.selectById(prereqId);
            if (target == null) {
                throw BizException.of(400, "KB_PREREQ_INVALID", "前置知识点不存在：" + prereqId);
            }
            if (ContentStatus.RETIRED.getCode().equals(target.getStatus())) {
                throw BizException.of(400, "KB_PREREQ_INVALID", "前置知识点已下线：" + target.getTitle());
            }
        }
        Map<Long, List<Long>> edges = new HashMap<>();
        for (KpPrereq edge : kpPrereqMapper.selectAll()) {
            edges.computeIfAbsent(edge.getKpId(), key -> new ArrayList<>()).add(edge.getPrereqKpId());
        }
        edges.put(kpId, new ArrayList<>(ids));
        Map<Long, String> titles = new HashMap<>();
        for (KpMain row : kpMainMapper.selectList(null)) {
            titles.put(row.getId(), row.getTitle());
        }
        PrereqCycleChecker.check(edges, titles, kpId);
        kpPrereqMapper.deleteByKpId(kpId);
        for (Long prereqId : ids) {
            kpPrereqMapper.insert(new KpPrereq(kpId, prereqId));
        }
    }

    /**
     * baseline 口径：levelReq 中 stopLayer 非空的档位视为已标，
     * 按 LevelTarget 枚举序（junior→expert）逗号拼接。
     */
    private String computeBaselineLevels(Map<String, LevelReq> levelReq) {
        if (levelReq == null) {
            return "";
        }
        List<String> codes = new ArrayList<>();
        for (LevelTarget target : LevelTarget.values()) {
            LevelReq req = levelReq.get(target.getCode());
            if (req != null && req.stopLayer() != null && !req.stopLayer().isBlank()) {
                codes.add(target.getCode());
            }
        }
        return String.join(",", codes);
    }

    private List<RubricGroup> assignPointIds(List<RubricGroup> rubric, Set<String> existingIds) {
        if (rubric == null) {
            return List.of();
        }
        List<RubricGroup> result = new ArrayList<>();
        for (RubricGroup group : rubric) {
            if (group == null) {
                continue;
            }
            List<RubricPoint> points = new ArrayList<>();
            if (group.points() != null) {
                for (RubricPoint point : group.points()) {
                    if (point == null) {
                        continue;
                    }
                    String pointId = point.pointId();
                    if (pointId == null || pointId.isBlank()) {
                        do {
                            pointId = "p" + String.format("%08x", RANDOM.nextInt());
                        } while (!existingIds.add(pointId));
                    } else {
                        existingIds.add(pointId);
                    }
                    points.add(new RubricPoint(pointId, point.level(), point.text()));
                }
            }
            result.add(new RubricGroup(group.layerKey(), points));
        }
        return result;
    }

    private Set<String> collectPointIds(List<RubricGroup> rubric) {
        Set<String> ids = new HashSet<>();
        if (rubric == null) {
            return ids;
        }
        for (RubricGroup group : rubric) {
            if (group == null || group.points() == null) {
                continue;
            }
            for (RubricPoint point : group.points()) {
                if (point != null && point.pointId() != null && !point.pointId().isBlank()) {
                    ids.add(point.pointId());
                }
            }
        }
        return ids;
    }

    private List<String> readVariants(String json) {
        return readJson(json, new TypeReference<List<String>>() {
        }, List.of());
    }

    private List<FollowupLayer> readFollowups(String json) {
        return readJson(json, new TypeReference<List<FollowupLayer>>() {
        }, List.of());
    }

    private List<RubricGroup> readRubric(String json) {
        return readJson(json, new TypeReference<List<RubricGroup>>() {
        }, List.of());
    }

    private Map<String, String> readSamples(String json) {
        return readJson(json, new TypeReference<Map<String, String>>() {
        }, Map.of());
    }

    private Map<String, LevelReq> readLevelReq(String json) {
        return readJson(json, new TypeReference<Map<String, LevelReq>>() {
        }, Map.of());
    }

    private <T> T readJson(String json, TypeReference<T> type, T empty) {
        if (json == null || json.isBlank()) {
            return empty;
        }
        return JSON.readValue(json, type);
    }

    private String writeVariants(List<String> variants) {
        return variants == null ? "[]" : JSON.writeValueAsString(variants);
    }

    private String writeFollowups(List<FollowupLayer> followups) {
        return followups == null ? "[]" : JSON.writeValueAsString(followups);
    }

    private String writeJsonMap(Map<?, ?> map) {
        return map == null ? "{}" : JSON.writeValueAsString(map);
    }

    private String nowText() {
        return LocalDateTime.now(ZoneOffset.UTC).format(SQLITE_DATETIME);
    }

    private String nowTextPlus(Period period) {
        return LocalDateTime.now(ZoneOffset.UTC).plus(period).format(SQLITE_DATETIME);
    }
}
