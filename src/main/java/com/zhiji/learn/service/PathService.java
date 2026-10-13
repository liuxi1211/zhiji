package com.zhiji.learn.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.constant.ConstantRegistry;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.KbDoc;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.domain.KbTopic;
import com.zhiji.kb.repo.KbDocMapper;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.repo.KbTopicMapper;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.LevelRule;
import com.zhiji.learn.domain.LearnState;
import com.zhiji.learn.domain.LearnStatus;
import com.zhiji.profile.domain.PrfClaim;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.domain.ProfileStatus;
import com.zhiji.profile.repo.PrfClaimMapper;
import com.zhiji.profile.service.ProfileService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 学习路径服务（核心模块定义 4.2）：把题库按当前档案的画像组织成有序的学习阶段与学习单元。
 *
 * 组织规则（每次实时推导，不落表，R1——重生成 = 重新请求视图，天然不动已积累进度）：
 * - 四路信号取并集准入：S1 技能条锚定知识点（主攻）/ S2 目标职级基线必问集（打底，level_rules.required）
 *   / S3 备考 JD 命中（定制）/ S4 复盘信号回流（一期空集，T13 接入）。并集之外不进路径、不留痕。
 * - 阶段序列（R2）：基础阶段（P∩S2，跨域不分域，按主题分单元）→ 各领域阶段（剩余按 domain_code 分组，
 *   按域内 kp 数降序）；"JD 相关/高频"不独立成段，以领域阶段内排序与标识体现。
 * - 单元内排序：JD 命中与主张锚定优先并打标 → 三路频率信号降序 → 难度升序。
 * - 前置锁定（R6）：kp 的 prerequisites 中存在「路径集内」且未推进的前置 → locked；单元任一成员 locked 即锁定；
 *   前置不在路径集内时忽略该前置（用户无学习通道，锁定即死局）。
 */
@Service
public class PathService {

    /** 信号标识：主张锚定 / 职级基线 / JD 命中（与前端 KpSignal 字面量对齐）。 */
    private static final String SIG_CLAIM = "claim";
    private static final String SIG_BASELINE = "baseline";
    private static final String SIG_JD = "jd";

    private static final List<String> DIFF_ORDER = List.of("junior", "mid", "senior", "expert");

    /** JD 分词停用词（R3）：篇幅词与程度词，不承载技术信息，参与匹配会大量误报。 */
    private static final Set<String> JD_STOPWORDS = Set.of(
            "熟悉", "掌握", "了解", "精通", "经验", "优先", "相关", "负责", "参与", "具备", "能力",
            "以上", "以及", "岗位", "职责", "要求", "招聘", "团队", "工作", "项目", "熟练",
            "良好", "较强", "优秀的", "有较", "能独立", "或者", "其他", "以上学历", "及以上");

    private final KbKpMapper kpMapper;
    private final KbTopicMapper topicMapper;
    private final KbDocMapper docMapper;
    private final PrfClaimMapper claimMapper;
    private final LearnStateService learnStateService;
    private final ProfileService profileService;
    private final ConstantRegistry constantRegistry;
    private final JsonMapper jsonMapper = new JsonMapper();

    public PathService(KbKpMapper kpMapper, KbTopicMapper topicMapper, KbDocMapper docMapper,
            PrfClaimMapper claimMapper, LearnStateService learnStateService, ProfileService profileService,
            ConstantRegistry constantRegistry) {
        this.kpMapper = kpMapper;
        this.topicMapper = topicMapper;
        this.docMapper = docMapper;
        this.claimMapper = claimMapper;
        this.learnStateService = learnStateService;
        this.profileService = profileService;
        this.constantRegistry = constantRegistry;
    }

    // —— 视图契约（与前端 types.ts 一一对应） ——

    public record PathKpView(long id, String title, String difficulty, List<String> signals, String state,
            boolean locked, int freqTotal, int followupLayers, int docCount) {
    }

    public record PathUnitView(long topicId, String topicName, int kpCount, int done, int total, boolean locked,
            List<String> signals, List<PathKpView> kps) {
    }

    public record PathStageView(String key, String kind, String domainCode, String domainName, String title,
            int done, int total, List<PathUnitView> units) {
    }

    public record PathSummary(int done, int total, int passCount, int quickCount, int weakCount,
            Long nextTopicId, String nextTopicName, String nextStageTitle) {
    }

    public record AbandonedView(long kpId, String title, String reason, String abandonedAt) {
    }

    public record PathView(long profileId, String profileName, String targetLevel, PathSummary summary,
            List<PathStageView> stages, List<AbandonedView> abandoned) {
    }

    // —— 主入口 ——

    /** 当前档案（is_active）的学习路径；无当前档案或未定稿 → LEARN_PROFILE_NOT_FINALIZED（R11）。 */
    public PathView buildForCustomer(long customerId) {
        PrfProfile profile = profileService.getActiveByCustomer(customerId);
        if (profile == null) {
            throw BizException.of(400, "LEARN_PROFILE_NOT_FINALIZED", "还没有当前档案，先建档并定稿再开始学习");
        }
        if (!ProfileStatus.FINALIZED.getCode().equals(profile.getStatus())) {
            throw BizException.of(400, "LEARN_PROFILE_NOT_FINALIZED", "档案尚未定稿，定稿后才能生成学习路径");
        }
        return buildPath(profile);
    }

    /** 路径推导主算法（R1–R6）。 */
    public PathView buildPath(PrfProfile profile) {
        long profileId = profile.getId();
        String targetLevel = profile.getTargetLevel();

        // ① 信号采集
        List<KbKp> available = kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                .eq(KbKp::getStatus, "available"));
        Map<Long, KbKp> byId = available.stream()
                .collect(Collectors.toMap(KbKp::getId, kp -> kp, (a, b) -> a, LinkedHashMap::new));

        Set<Long> claimIds = claimAnchorIds(profileId);
        Set<Long> baselineIds = baselineIds(available, targetLevel);
        Set<Long> jdIds = jdHitIds(available, profile.getJdText());
        // S4 复盘信号一路一期空集（T13 接入；并集准入口径不变）
        Set<Long> signalIds = new LinkedHashSet<>();
        signalIds.addAll(claimIds);
        signalIds.addAll(baselineIds);
        signalIds.addAll(jdIds);

        // ② 并集准入（均 available，已由查询过滤）
        List<KbKp> pathKps = available.stream().filter(kp -> signalIds.contains(kp.getId())).toList();
        Set<Long> pathIdSet = pathKps.stream().map(KbKp::getId).collect(Collectors.toSet());

        // ③ 状态与锁定
        Map<Long, LearnState> states = learnStateService.byProfile(profileId);
        Set<Long> progressed = learnStateService.progressedSet(profileId);
        Set<Long> abandonedIds = states.values().stream()
                .filter(row -> LearnStatus.ABANDONED.getCode().equals(row.getStatus()))
                .map(LearnState::getKpId).collect(Collectors.toSet());
        // 放弃是唯一的人工裁剪例外（4.2）：移出单元、进放弃清单；进度与汇总均不含放弃题
        List<KbKp> stageKps = pathKps.stream().filter(kp -> !abandonedIds.contains(kp.getId())).toList();
        Set<Long> lockIds = lockedIds(stageKps, pathIdSet, progressed, byId);
        Map<Long, Integer> docCounts = docCountsByKp();

        // ④ 组织
        List<KbKp> baselineKps = stageKps.stream()
                .filter(kp -> baselineIds.contains(kp.getId()))
                .toList();
        List<KbKp> restKps = stageKps.stream()
                .filter(kp -> !baselineIds.contains(kp.getId()))
                .toList();

        List<PathStageView> stages = new ArrayList<>();
        if (!baselineKps.isEmpty()) {
            stages.add(buildStage("baseline", "baseline", null, null, "职级必问 · 不分领域",
                    baselineKps, claimIds, baselineIds, jdIds, states, lockIds, docCounts));
        }
        Map<String, List<KbKp>> byDomain = restKps.stream()
                .collect(Collectors.groupingBy(KbKp::getDomainCode, LinkedHashMap::new, Collectors.toList()));
        List<Map.Entry<String, List<KbKp>>> domainEntries = byDomain.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .toList();
        int index = 0;
        for (Map.Entry<String, List<KbKp>> entry : domainEntries) {
            index++;
            stages.add(buildStage(entry.getKey(), "domain", entry.getKey(), domainName(entry.getKey()),
                    domainName(entry.getKey()), entry.getValue(), claimIds, baselineIds, jdIds,
                    states, lockIds, docCounts, index));
        }

        // ⑤ 汇总（口径 = 阶段内可见题，不含放弃题）
        int total = stageKps.size();
        int done = (int) stageKps.stream().filter(kp -> progressed.contains(kp.getId())).count();
        int passCount = (int) stageKps.stream().filter(kp -> is(state(states, kp.getId()), LearnStatus.PASS)).count();
        int quickCount = (int) stageKps.stream().filter(kp -> is(state(states, kp.getId()), LearnStatus.QUICK)).count();
        int weakCount = (int) stageKps.stream().filter(kp -> is(state(states, kp.getId()), LearnStatus.WEAK)).count();
        PathUnitView next = firstActionable(stages);
        PathSummary summary = new PathSummary(done, total, passCount, quickCount, weakCount,
                next == null ? null : next.topicId(), next == null ? null : next.topicName(),
                next == null ? null : stageTitleOf(stages, next));

        return new PathView(profileId, profile.getName(), targetLevel, summary, stages,
                abandonedList(profileId, byId));
    }

    /** 单阶段构建：按主题分单元，单元内排序（JD/主张优先 → 频率降序 → 难度升序）。 */
    private PathStageView buildStage(String key, String kind, String domainCode, String domainName, String title,
            List<KbKp> kps, Set<Long> claimIds, Set<Long> baselineIds, Set<Long> jdIds,
            Map<Long, LearnState> states, Set<Long> lockIds, Map<Long, Integer> docCounts) {
        return buildStage(key, kind, domainCode, domainName, title, kps, claimIds, baselineIds, jdIds,
                states, lockIds, docCounts, 0);
    }

    private PathStageView buildStage(String key, String kind, String domainCode, String domainName, String title,
            List<KbKp> kps, Set<Long> claimIds, Set<Long> baselineIds, Set<Long> jdIds,
            Map<Long, LearnState> states, Set<Long> lockIds, Map<Long, Integer> docCounts, int order) {
        // 单元 = 主题；同主题 kp 聚在一起
        Map<Long, List<KbKp>> byTopic = kps.stream()
                .collect(Collectors.groupingBy(KbKp::getTopicId, LinkedHashMap::new, Collectors.toList()));
        List<PathUnitView> units = new ArrayList<>();
        int stageDone = 0;
        int stageTotal = 0;
        for (Map.Entry<Long, List<KbKp>> entry : byTopic.entrySet()) {
            List<KbKp> members = new ArrayList<>(entry.getValue());
            members.sort(kpComparator(claimIds, jdIds));
            List<PathKpView> kpViews = new ArrayList<>();
            int unitDone = 0;
            boolean unitLocked = false;
            Set<String> unitSignals = new LinkedHashSet<>();
            for (KbKp kp : members) {
                List<String> signals = signalsOf(kp.getId(), claimIds, baselineIds, jdIds);
                unitSignals.addAll(signals);
                String state = state(states, kp.getId());
                boolean locked = lockIds.contains(kp.getId());
                if (locked) {
                    unitLocked = true;
                }
                if (LearnStatus.isProgressed(state)) {
                    unitDone++;
                }
                kpViews.add(new PathKpView(kp.getId(), kp.getTitle(), kp.getDifficulty(), signals, state, locked,
                        freqTotal(kp), followupLayers(kp), docCounts.getOrDefault(kp.getId(), 0)));
            }
            stageDone += unitDone;
            stageTotal += members.size();
            units.add(new PathUnitView(entry.getKey(), topicName(entry.getKey()), members.size(), unitDone,
                    members.size(), unitLocked, new ArrayList<>(unitSignals), kpViews));
        }
        return new PathStageView(key, kind, domainCode, domainName,
                order > 0 ? order + " " + title : title, stageDone, stageTotal, units);
    }

    /** 单元内排序：JD 命中与主张锚定优先 → 频率三路合计降序 → 难度升序。 */
    private Comparator<KbKp> kpComparator(Set<Long> claimIds, Set<Long> jdIds) {
        return Comparator
                .comparingInt((KbKp kp) -> (jdIds.contains(kp.getId()) || claimIds.contains(kp.getId())) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(this::freqTotal).reversed())
                .thenComparingInt(kp -> DIFF_ORDER.indexOf(kp.getDifficulty() == null ? "mid" : kp.getDifficulty()));
    }

    // —— 信号 ——

    /** S1 主张锚定知识点并集：prf_claim.anchor_ids 全部 kp id（R4 前的 S1）。 */
    private Set<Long> claimAnchorIds(long profileId) {
        Set<Long> ids = new LinkedHashSet<>();
        List<PrfClaim> claims = claimMapper.selectList(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profileId));
        for (PrfClaim claim : claims) {
            ids.addAll(fromJsonList(claim.getAnchorIds(), Long.class));
        }
        return ids;
    }

    /** S2 目标职级基线必问集：level_rules 含 {level=targetLevel, required=true} 的 available kp。 */
    private Set<Long> baselineIds(List<KbKp> available, String targetLevel) {
        Set<Long> ids = new LinkedHashSet<>();
        if (targetLevel == null || targetLevel.isBlank()) {
            return ids;
        }
        for (KbKp kp : available) {
            for (LevelRule rule : levelRules(kp)) {
                if (targetLevel.equals(rule.level()) && rule.required()) {
                    ids.add(kp.getId());
                    break;
                }
            }
        }
        return ids;
    }

    /** S3 JD 命中：token 化包含匹配（R3）。 */
    private Set<Long> jdHitIds(List<KbKp> available, String jdText) {
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> tokens = tokenize(jdText);
        if (tokens.isEmpty()) {
            return ids;
        }
        for (KbKp kp : available) {
            String haystack = String.join("\n",
                    nullToEmpty(kp.getTitle()), nullToEmpty(kp.getSummary()),
                    nullToEmpty(kp.getVariants()), nullToEmpty(kp.getFrameworkMd()));
            if (tokens.stream().anyMatch(haystack::contains)) {
                ids.add(kp.getId());
            }
        }
        return ids;
    }

    /** R3 分词：按非中英文数字切分，取长度 ≥2 且不在停用词表的 token。 */
    private Set<String> tokenize(String jdText) {
        if (jdText == null || jdText.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : jdText.split("[^\\p{IsHan}A-Za-z0-9]+")) {
            String trimmed = token.strip();
            if (trimmed.length() >= 2 && !JD_STOPWORDS.contains(trimmed)
                    && !trimmed.matches("\\d+")) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    // —— 锁定（R6） ——

    private Set<Long> lockedIds(List<KbKp> pathKps, Set<Long> pathIdSet, Set<Long> progressed,
            Map<Long, KbKp> byId) {
        Set<Long> locked = new LinkedHashSet<>();
        for (KbKp kp : pathKps) {
            for (Long prereq : prerequisites(kp)) {
                // 前置不在路径集内时忽略（用户无学习通道，锁定即死局）；在路径集内且未推进则锁定
                if (pathIdSet.contains(prereq) && !progressed.contains(prereq)) {
                    locked.add(kp.getId());
                    break;
                }
            }
        }
        return locked;
    }

    // —— 工具 ——

    private List<LevelRule> levelRules(KbKp kp) {
        return fromJsonList(kp.getLevelRules(), LevelRule.class);
    }

    private List<Long> prerequisites(KbKp kp) {
        return fromJsonList(kp.getPrerequisites(), Long.class);
    }

    private int followupLayers(KbKp kp) {
        return fromJsonList(kp.getFollowups(), KbContentService.Followup.class).stream()
                .filter(f -> "active".equals(f.status()))
                .mapToInt(KbContentService.Followup::layer)
                .max().orElse(0);
    }

    private int freqTotal(KbKp kp) {
        return nz(kp.getFreqManual()) + nz(kp.getFreqSignal()) + nz(kp.getFreqCoverage());
    }

    private Map<Long, Integer> docCountsByKp() {
        Map<Long, Integer> counts = new HashMap<>();
        for (KbDoc doc : docMapper.selectList(new LambdaQueryWrapper<KbDoc>()
                .eq(KbDoc::getStatus, "available")
                .isNotNull(KbDoc::getKpId))) {
            counts.merge(doc.getKpId(), 1, Integer::sum);
        }
        return counts;
    }

    private List<String> signalsOf(long kpId, Set<Long> claimIds, Set<Long> baselineIds, Set<Long> jdIds) {
        List<String> signals = new ArrayList<>();
        if (claimIds.contains(kpId)) {
            signals.add(SIG_CLAIM);
        }
        if (baselineIds.contains(kpId)) {
            signals.add(SIG_BASELINE);
        }
        if (jdIds.contains(kpId)) {
            signals.add(SIG_JD);
        }
        return signals;
    }

    private String state(Map<Long, LearnState> states, long kpId) {
        LearnState row = states.get(kpId);
        return row == null ? LearnStatus.LEARNING.getCode() : row.getStatus();
    }

    private boolean is(String state, LearnStatus target) {
        return target.getCode().equals(state);
    }

    /** 首个「未完成且未锁定」的单元（"下一步学哪"），按阶段顺序取第一个。 */
    private PathUnitView firstActionable(List<PathStageView> stages) {
        for (PathStageView stage : stages) {
            for (PathUnitView unit : stage.units()) {
                if (!unit.locked() && unit.done() < unit.total()) {
                    return unit;
                }
            }
        }
        return null;
    }

    private String stageTitleOf(List<PathStageView> stages, PathUnitView unit) {
        for (PathStageView stage : stages) {
            if (stage.units().contains(unit)) {
                return stage.title();
            }
        }
        return null;
    }

    private List<AbandonedView> abandonedList(long profileId, Map<Long, KbKp> byId) {
        List<AbandonedView> list = new ArrayList<>();
        for (LearnState row : learnStateService.listAbandoned(profileId)) {
            KbKp kp = byId.get(row.getKpId());
            // 已下线/被合并的知识点不再出现在放弃清单（下线全链路封死）
            if (kp == null) {
                continue;
            }
            list.add(new AbandonedView(row.getKpId(), kp.getTitle(), row.getAbandonReason(), row.getUpdatedAt()));
        }
        return list;
    }

    private String topicName(long topicId) {
        KbTopic topic = topicMapper.selectById(topicId);
        return topic == null ? "" : topic.getName();
    }

    private String domainName(String domainCode) {
        List<ConstantRegistry.Item> domains = constantRegistry.items("domain");
        if (domains != null) {
            for (ConstantRegistry.Item item : domains) {
                if (item.code().equals(domainCode)) {
                    return item.name();
                }
            }
        }
        return domainCode;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private <T> List<T> fromJsonList(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return jsonMapper.readValue(json,
                jsonMapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
