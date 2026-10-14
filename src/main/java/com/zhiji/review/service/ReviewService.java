package com.zhiji.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.KbDoc;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.repo.KbDocMapper;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.AnswerSample;
import com.zhiji.kb.service.KbContentService.LevelRule;
import com.zhiji.kb.service.KbContentService.Variant;
import com.zhiji.learn.domain.LearnState;
import com.zhiji.learn.domain.LearnStatus;
import com.zhiji.learn.service.LearnStateService;
import com.zhiji.profile.domain.PrfClaim;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.domain.ProfileStatus;
import com.zhiji.profile.repo.PrfClaimMapper;
import com.zhiji.profile.service.ProfileService;
import com.zhiji.review.domain.ReviewLog;
import com.zhiji.review.repo.ReviewLogMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 今日复习服务（核心模块定义 4.3，T12）：每天打开知道刷什么 + 碎片时间随机抽取。
 *
 * 两种形态（R5/R6/R7）：
 * - 今日清单：四路信号槽位合成（到期复测 ≤3 / 弱点 1 / 抽查 1（1/3 概率）/ 技能条新题 ≤2，上限 5），
 *   已复测的题因 due_at 后移自然出列，当日不重复。
 * - 随机抽取：池 = 复习池（quick/pass/weak）∪ 锚定未学题，支持域过滤与 exclude；
 *   两个动态规则第一版就有——连对下调抽中概率（×1/(1+streak)）、连错掺低难度（×3），
 *   全部是代码权重（weightOf 纯函数），模型从不参与。
 *
 * 复测结果只更新复习参数与 review_log，不回写 learn_state 五态（4.3"今日复习不回写学习路径"，R2）；
 * 未学题（new）自评只落流水不建排期（复习不产生学习推进，正式拿下走练习模式，R8/D-6）。
 * 零 AI 用途：抽题与排期全是代码算法（6.4 清单无需登记）。
 */
@Service
public class ReviewService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 今日清单槽位上限（R5/D-4：3 分钟量级的产品承诺）。 */
    private static final int CAP_DUE = 3;
    private static final int CAP_WEAK = 1;
    private static final int CAP_NEW = 2;
    /** 抽查触发阈值（D-5）：coin < 1/3 弹出，每次打开 today 独立判定。 */
    private static final double SPOT_PROBABILITY = 1.0 / 3.0;

    private static final List<String> DIFF_ORDER = List.of("junior", "mid", "senior", "expert");
    private static final double BASE_WEIGHT = 1.0;
    private static final double DUE_WEIGHT = 3.0;
    private static final double WEAK_WEIGHT = 3.0;
    private static final double SPOT_WEIGHT = 0.5;
    private static final double NEW_WEIGHT = 0.8;
    private static final double WRONG_STREAK_BOOST = 3.0;

    private final KbKpMapper kpMapper;
    private final KbDocMapper docMapper;
    private final PrfClaimMapper claimMapper;
    private final LearnStateService learnStateService;
    private final ReviewLogMapper reviewLogMapper;
    private final ProfileService profileService;
    private final Random random;
    private final JsonMapper jsonMapper = new JsonMapper();

    public ReviewService(KbKpMapper kpMapper, KbDocMapper docMapper, PrfClaimMapper claimMapper,
            LearnStateService learnStateService, ReviewLogMapper reviewLogMapper,
            ProfileService profileService, Random random) {
        this.kpMapper = kpMapper;
        this.docMapper = docMapper;
        this.claimMapper = claimMapper;
        this.learnStateService = learnStateService;
        this.reviewLogMapper = reviewLogMapper;
        this.profileService = profileService;
        this.random = random;
    }

    // —— 视图契约（与前端 types.ts 一一对应） ——

    /** 题目卡片：题面自测 + 自评后展开三档样例对照；轻载荷不含叙述框架/评分要点（正式练习再看）。 */
    public record ReviewItemView(long kpId, String title, String difficulty, String state, List<String> tags,
            int followupLayers, int docCount, List<Variant> variants, List<AnswerSample> answerSamples,
            String dueAt) {
    }

    public record ReviewStats(int due, int weak, int quick, int newCount) {
    }

    public record ReviewTodayView(String date, String profileName, String targetLevel,
            List<ReviewItemView> items, ReviewStats stats) {
    }

    public record ReviewResultView(long kpId, String state, String dueAt, int intervalDays, int failCount) {
    }

    public record DrawResp(ReviewItemView item, int poolSize) {
    }

    /** 权重上下文（weightOf 纯函数入参，R7）。 */
    public record KpWeightCtx(String state, boolean due, String difficulty, int correctStreak,
            boolean recentWrongStreak) {
    }

    // —— 今日清单 ——

    public ReviewTodayView today(long customerId) {
        PrfProfile profile = requireFinalizedProfile(customerId);
        long profileId = profile.getId();
        String now = now();

        // 信号采集（R5）
        List<LearnState> dueRows = learnStateService.dueRows(profileId);
        List<LearnState> progressedRows = learnStateService.progressedRows(profileId);
        Map<Long, LearnState> progressedById = progressedRows.stream()
                .collect(Collectors.toMap(LearnState::getKpId, row -> row, (a, b) -> a, LinkedHashMap::new));
        List<LearnState> weakRows = progressedRows.stream()
                .filter(row -> LearnStatus.WEAK.getCode().equals(row.getStatus()))
                .toList();
        List<LearnState> quickRows = progressedRows.stream()
                .filter(row -> LearnStatus.QUICK.getCode().equals(row.getStatus()))
                .toList();

        List<KbKp> available = listAvailableKps();
        Map<Long, KbKp> availableById = available.stream()
                .collect(Collectors.toMap(KbKp::getId, kp -> kp, (a, b) -> a, LinkedHashMap::new));
        // 锚定未学：主张锚定 ∪ 职级基线，排除 progressed 与 abandoned（复习池与新题不重叠）
        Set<Long> anchorIds = anchorIds(profileId, profile.getTargetLevel());
        Set<Long> progressedIds = progressedById.keySet();
        List<KbKp> newKps = available.stream()
                .filter(kp -> anchorIds.contains(kp.getId()) && !progressedIds.contains(kp.getId()))
                .toList();

        // 槽位合成（R5，去重）：① 抽查（coin 触发，quick 行优先认领）→ ② 到期复测（非 weak）
        // → ③ 弱点（weak & 到期）→ ④ 技能条新题。quick 行同属到期，spot 先认领避免被 due 槽吞掉。
        Map<Long, List<String>> tagByKp = new LinkedHashMap<>();
        Map<Long, LearnState> stateByKp = new LinkedHashMap<>();
        List<LearnState> dueNonWeak = dueRows.stream()
                .filter(row -> !LearnStatus.WEAK.getCode().equals(row.getStatus())).toList();
        List<LearnState> dueWeak = dueRows.stream()
                .filter(row -> LearnStatus.WEAK.getCode().equals(row.getStatus())).toList();
        LearnState spotRow = null;
        if (shouldIncludeSpot(random.nextDouble())) {
            spotRow = quickRows.stream()
                    .filter(row -> availableById.containsKey(row.getKpId()))
                    .findFirst().orElse(null);
            if (spotRow != null) {
                tagByKp.put(spotRow.getKpId(), new ArrayList<>(List.of("spot")));
                stateByKp.put(spotRow.getKpId(), spotRow);
            }
        }
        List<KbKp> dueKps = dueNonWeak.stream()
                .map(row -> availableById.get(row.getKpId())).filter(kp -> kp != null)
                .filter(kp -> !tagByKp.containsKey(kp.getId()))
                .limit(CAP_DUE).toList();
        for (KbKp kp : dueKps) {
            tagByKp.put(kp.getId(), new ArrayList<>(List.of("due")));
            stateByKp.put(kp.getId(), progressedById.get(kp.getId()));
        }
        LearnState weakRow = dueWeak.stream()
                .filter(row -> availableById.containsKey(row.getKpId()) && !tagByKp.containsKey(row.getKpId()))
                .findFirst().orElse(null);
        if (weakRow != null) {
            tagByKp.put(weakRow.getKpId(), new ArrayList<>(List.of("weak")));
            stateByKp.put(weakRow.getKpId(), weakRow);
        }
        List<KbKp> newPicked = newKps.stream()
                .filter(kp -> !tagByKp.containsKey(kp.getId()))
                .sorted(Comparator.comparingInt((KbKp kp) -> DIFF_ORDER.indexOf(
                                kp.getDifficulty() == null ? "mid" : kp.getDifficulty()))
                        .thenComparing(Comparator.comparingInt(this::freqTotal).reversed()))
                .limit(CAP_NEW)
                .toList();
        for (KbKp kp : newPicked) {
            tagByKp.put(kp.getId(), new ArrayList<>(List.of("new")));
            stateByKp.put(kp.getId(), null);
        }

        Map<Long, Integer> docCounts = docCountsByKp();
        List<ReviewItemView> items = new ArrayList<>();
        for (Map.Entry<Long, List<String>> entry : tagByKp.entrySet()) {
            KbKp kp = availableById.get(entry.getKey());
            if (kp == null) {
                continue;
            }
            LearnState row = stateByKp.get(entry.getKey());
            items.add(toItemView(kp, entry.getValue(), row == null ? LearnStatus.LEARNING.getCode() : row.getStatus(),
                    row == null ? null : row.getDueAt(), docCounts.getOrDefault(kp.getId(), 0)));
        }

        ReviewStats stats = new ReviewStats(
                (int) dueRows.stream().filter(row -> availableById.containsKey(row.getKpId())).count(),
                weakRows.size(), quickRows.size(), newKps.size());
        return new ReviewTodayView(LocalDate.now().toString(), profile.getName(), profile.getTargetLevel(),
                items, stats);
    }

    // —— 随机抽取 ——

    public DrawResp draw(long customerId, String domainCode, List<Long> excludeIds) {
        PrfProfile profile = requireFinalizedProfile(customerId);
        long profileId = profile.getId();

        Set<Long> exclude = excludeIds == null ? Set.of() : new LinkedHashSet<>(excludeIds);
        List<KbKp> available = listAvailableKps();
        Map<Long, KbKp> availableById = available.stream()
                .collect(Collectors.toMap(KbKp::getId, kp -> kp, (a, b) -> a, LinkedHashMap::new));
        Map<Long, LearnState> progressedById = learnStateService.progressedRows(profileId).stream()
                .collect(Collectors.toMap(LearnState::getKpId, row -> row, (a, b) -> a, LinkedHashMap::new));
        Set<Long> anchorIds = anchorIds(profileId, profile.getTargetLevel());

        // 池 = 复习池 ∪ 锚定未学（R6）；域过滤 + exclude 剔除
        Map<Long, LearnState> pool = new LinkedHashMap<>();
        for (LearnState row : progressedById.values()) {
            KbKp kp = availableById.get(row.getKpId());
            if (kp == null || exclude.contains(kp.getId())
                    || (domainCode != null && !domainCode.isBlank() && !domainCode.equals(kp.getDomainCode()))) {
                continue;
            }
            pool.put(kp.getId(), row);
        }
        for (KbKp kp : available) {
            if (!anchorIds.contains(kp.getId()) || progressedById.containsKey(kp.getId()) || exclude.contains(kp.getId())
                    || (domainCode != null && !domainCode.isBlank() && !domainCode.equals(kp.getDomainCode()))) {
                continue;
            }
            pool.put(kp.getId(), null);
        }
        if (pool.isEmpty()) {
            throw BizException.of(400, "REVIEW_POOL_EMPTY", "可抽取的题目都抽过了：清一清排除列表再来，或换 domains 看看");
        }

        // 权重（R7）：连对衰减 + 连错掺低难；streak/连错从 review_log 推导
        String now = now();
        Map<Long, Integer> streakByKp = new HashMap<>();
        boolean recentWrongStreak = recentWrongStreak(profileId, now);
        for (Map.Entry<Long, LearnState> entry : pool.entrySet()) {
            if (entry.getValue() != null) {
                streakByKp.put(entry.getKey(), correctStreak(profileId, entry.getKey(), now));
            }
        }
        List<KpWeightCtx> ctxs = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, LearnState> entry : pool.entrySet()) {
            KbKp kp = availableById.get(entry.getKey());
            LearnState row = entry.getValue();
            boolean due = row != null && (row.getDueAt() == null || row.getDueAt().compareTo(now) <= 0);
            ctxs.add(new KpWeightCtx(row == null ? LearnStatus.LEARNING.getCode() : row.getStatus(), due,
                    kp.getDifficulty(), streakByKp.getOrDefault(entry.getKey(), 0), recentWrongStreak));
            ids.add(entry.getKey());
        }
        int index = weightedPick(ctxs);
        Long pickedId = ids.get(index);
        KbKp picked = availableById.get(pickedId);
        LearnState row = pool.get(pickedId);
        ReviewItemView item = toItemView(picked, List.of(row == null ? "new" : "draw"),
                row == null ? LearnStatus.LEARNING.getCode() : row.getStatus(),
                row == null ? null : row.getDueAt(), docCountsByKp().getOrDefault(picked.getId(), 0));
        return new DrawResp(item, pool.size());
    }

    // —— 自评结果 ——

    /**
     * 复习自评（R2/R3/R4/R8）：落 review_log 一条；progressed 行更新排期与 fail_count（不动五态）；
     * 未学行只落流水（不建排期、不改状态——复习不产生学习推进）。
     */
    @Transactional
    public ReviewResultView result(long customerId, long kpId, boolean known, String mode) {
        PrfProfile profile = requireFinalizedProfile(customerId);
        long profileId = profile.getId();
        String m = "draw".equals(mode) ? "draw" : "today";

        // 连对 streak（含本次）在插 log 前推导：本次 known + 此前连续 known 条数
        int streak = known ? 1 + correctStreakBeforeNow(profileId, kpId) : 0;

        ReviewLog log = new ReviewLog();
        log.setProfileId(profileId);
        log.setKpId(kpId);
        log.setKnown(known ? 1 : 0);
        log.setMode(m);
        log.setCreatedAt(now());
        reviewLogMapper.insert(log);

        LearnState row = learnStateService.getOwned(profileId, kpId);
        boolean progressed = row != null && LearnStatus.isProgressed(row.getStatus());
        LearnState updated = progressed
                ? learnStateService.recordReviewResult(profileId, kpId, known, known ? streak : null)
                : null;
        return new ReviewResultView(kpId,
                updated == null ? (row == null ? LearnStatus.LEARNING.getCode() : row.getStatus()) : updated.getStatus(),
                updated == null ? null : updated.getDueAt(),
                updated == null || updated.getReviewIntervalDays() == null ? 0 : updated.getReviewIntervalDays(),
                updated == null || updated.getFailCount() == null ? 0 : updated.getFailCount());
    }

    // —— 权重与随机（纯函数，可单测，R7/D-2） ——

    /** 单题权重：base 1.0；due ×3；weak ×3；quick 抽验位 ×0.5；new ×0.8；连对衰减 ×1/(1+streak)；连错掺低难 ×3。 */
    public static double weightOf(KpWeightCtx ctx) {
        double w;
        switch (ctx.state()) {
            case "weak" -> w = BASE_WEIGHT * WEAK_WEIGHT;
            case "quick" -> w = BASE_WEIGHT * SPOT_WEIGHT;
            case "pass" -> w = BASE_WEIGHT;
            default -> w = BASE_WEIGHT * NEW_WEIGHT; // 未学（new）
        }
        if (ctx.due()) {
            w *= DUE_WEIGHT;
        }
        if (ctx.correctStreak() > 0) {
            w *= 1.0 / (1.0 + Math.min(ctx.correctStreak(), 3));
        }
        // 连错掺低难度已学题（quick/pass/weak 均为"已学"；new 不掺）
        if (ctx.recentWrongStreak() && isLearned(ctx.state()) && isLowDiff(ctx.difficulty())) {
            w *= WRONG_STREAK_BOOST;
        }
        return w;
    }

    /** 抽查触发（D-5）：coin < 1/3。 */
    public static boolean shouldIncludeSpot(double coin) {
        return coin < SPOT_PROBABILITY;
    }

    private static boolean isLearned(String state) {
        return LearnStatus.isProgressed(state);
    }

    private static boolean isNew(String state) {
        return LearnStatus.LEARNING.getCode().equals(state);
    }

    private static boolean isLowDiff(String difficulty) {
        return "junior".equals(difficulty) || "mid".equals(difficulty);
    }

    /** 加权随机取一个下标（随机仅在边界，权重逻辑全静态）。 */
    private int weightedPick(List<KpWeightCtx> ctxs) {
        double[] weights = new double[ctxs.size()];
        double total = 0;
        for (int i = 0; i < ctxs.size(); i++) {
            weights[i] = Math.max(weightOf(ctxs.get(i)), 1e-6);
            total += weights[i];
        }
        double coin = random.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (coin < acc) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /** 含本次的连续自评"会"次数（review_log 倒序扫到第一条"不会"为止；draw 池权重用）。 */
    private int correctStreak(long profileId, long kpId, String now) {
        List<ReviewLog> logs = reviewLogMapper.selectList(new LambdaQueryWrapper<ReviewLog>()
                .eq(ReviewLog::getProfileId, profileId)
                .eq(ReviewLog::getKpId, kpId)
                .orderByDesc(ReviewLog::getId));
        int streak = 0;
        for (ReviewLog log : logs) {
            if (log.getKnown() != null && log.getKnown() == 1) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /** 本次自评之前的连对条数（result 插 log 前调用，首次为 0 → 阶梯取 LADDER[0]=1 天）。 */
    private int correctStreakBeforeNow(long profileId, long kpId) {
        return correctStreak(profileId, kpId, now());
    }

    /** 最近 3 条流水（全档案）是否全为"不会"（连错检测，R7 掺低难规则）。 */
    private boolean recentWrongStreak(long profileId, String now) {
        List<ReviewLog> logs = reviewLogMapper.selectList(new LambdaQueryWrapper<ReviewLog>()
                .eq(ReviewLog::getProfileId, profileId)
                .orderByDesc(ReviewLog::getId)
                .last("LIMIT 3"));
        return logs.size() == 3 && logs.stream().allMatch(log -> log.getKnown() == null || log.getKnown() == 0);
    }

    // —— 信号与工具 ——

    /** 锚定未学来源（D-1 复制 PathService 同款口径）：主张锚定 ∪ 目标职级基线必问。 */
    private Set<Long> anchorIds(long profileId, String targetLevel) {
        Set<Long> ids = new LinkedHashSet<>();
        List<PrfClaim> claims = claimMapper.selectList(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profileId));
        for (PrfClaim claim : claims) {
            ids.addAll(fromJsonList(claim.getAnchorIds(), Long.class));
        }
        if (targetLevel != null && !targetLevel.isBlank()) {
            for (KbKp kp : listAvailableKps()) {
                for (LevelRule rule : fromJsonList(kp.getLevelRules(), LevelRule.class)) {
                    if (targetLevel.equals(rule.level()) && rule.required()) {
                        ids.add(kp.getId());
                        break;
                    }
                }
            }
        }
        return ids;
    }

    private List<KbKp> listAvailableKps() {
        return kpMapper.selectList(new LambdaQueryWrapper<KbKp>().eq(KbKp::getStatus, "available"));
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

    private ReviewItemView toItemView(KbKp kp, List<String> tags, String state, String dueAt, int docCount) {
        int layers = fromJsonList(kp.getFollowups(), KbContentService.Followup.class).stream()
                .filter(f -> "active".equals(f.status()))
                .mapToInt(KbContentService.Followup::layer).max().orElse(0);
        return new ReviewItemView(kp.getId(), kp.getTitle(), kp.getDifficulty(), state, tags, layers, docCount,
                fromJsonList(kp.getVariants(), Variant.class),
                fromJsonList(kp.getAnswerSamples(), AnswerSample.class), dueAt);
    }

    private PrfProfile requireFinalizedProfile(long customerId) {
        PrfProfile profile = profileService.getActiveByCustomer(customerId);
        if (profile == null) {
            throw BizException.of(400, "LEARN_PROFILE_NOT_FINALIZED", "还没有当前档案，先建档并定稿再开始复习");
        }
        if (!ProfileStatus.FINALIZED.getCode().equals(profile.getStatus())) {
            throw BizException.of(400, "LEARN_PROFILE_NOT_FINALIZED", "档案尚未定稿，定稿后才能开始复习");
        }
        return profile;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private <T> List<T> fromJsonList(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return jsonMapper.readValue(json, jsonMapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }
}
