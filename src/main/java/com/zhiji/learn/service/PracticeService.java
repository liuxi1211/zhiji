package com.zhiji.learn.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.judge.service.JudgeService;
import com.zhiji.judge.service.JudgeService.AnswerResp;
import com.zhiji.judge.service.JudgeService.SessionView;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.AnswerSample;
import com.zhiji.kb.service.KbContentService.DocBrief;
import com.zhiji.kb.service.KbContentService.KpView;
import com.zhiji.kb.service.KbContentService.Variant;
import com.zhiji.learn.domain.LearnState;
import com.zhiji.learn.domain.LearnStatus;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.service.ProfileService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 练习服务（核心模块定义 4.2）：双节奏练习的编排层。
 *
 * 练习模式：复用判定引擎（JudgeService），闭卷作答经逐层判定驱动下一步（下钻/换角度/回退/切题），
 * 判定通过才算完成（close 时 grade ∈ {pass,good,excellent} → learn_state=pass，R8）。
 * 速览模式：先自测后看三档样例、自评直接推进（quickEval → quick/weak），由 LearnController 直落
 * LearnStateService，不经本类。
 *
 * 归属：会话一律 customer 归属（created_by=档案 id），判定链路按档案计量 LLM 限额（6.3）；
 * 每次操作前过 JudgeService.assertOwnedByProfile 越权校验（404，R7）。
 */
@Service
public class PracticeService {

    private final JudgeService judgeService;
    private final KbContentService contentService;
    private final LearnStateService learnStateService;
    private final ProfileService profileService;

    public PracticeService(JudgeService judgeService, KbContentService contentService,
            LearnStateService learnStateService, ProfileService profileService) {
        this.judgeService = judgeService;
        this.contentService = contentService;
        this.learnStateService = learnStateService;
        this.profileService = profileService;
    }

    /** 练习会话视图（与前端 types.ts 一一对应）：判定会话 + kp 展示结构 + 挂载资料 + 目标刻度。 */
    public record PracticeSessionView(long id, long kpId, long topicId, String kpTitle, String kpSummary,
            String difficulty, String frameworkMd, String level, Integer stopLayer, int followupLayers,
            String status, String grade, String gradeNote, boolean degraded, String state,
            List<Variant> variants, List<AnswerSample> answerSamples, List<DocBrief> docs,
            List<KbContentService.ScoringPoint> scoringPoints, List<KbContentService.Followup> followups,
            List<JudgeService.AnswerView> answers, String createdAt, String closedAt) {
    }

    /** 题面预览（选题后、选节奏前）：kp 展示结构 + 目标刻度 + 挂载资料，不开判定会话。 */
    public record PracticeBriefView(long kpId, long topicId, String kpTitle, String kpSummary, String difficulty,
            String frameworkMd, String level, Integer stopLayer, int followupLayers, String state,
            List<Variant> variants, List<AnswerSample> answerSamples, List<DocBrief> docs,
            List<KbContentService.ScoringPoint> scoringPoints, List<KbContentService.Followup> followups) {
    }

    public record CloseResult(PracticeSessionView session, String state) {
    }

    // —— 预览与开局 ——

    /** 题面预览（原型屏 03）：双节奏卡与目标刻度在开会话前展示，速览模式无需判定会话。 */
    public PracticeBriefView brief(long customerId, long kpId) {
        PrfProfile profile = requireActiveProfile(customerId);
        KpView kp = requireAvailableKp(kpId);
        LearnState row = learnStateService.getOwned(profile.getId(), kpId);
        String state = row == null ? LearnStatus.LEARNING.getCode() : row.getStatus();
        return new PracticeBriefView(kp.id(), kp.topicId(), kp.title(), kp.summary(), kp.difficulty(),
                kp.frameworkMd(), profile.getTargetLevel(), stopLayerOf(kp, profile.getTargetLevel()),
                followupLayers(kp), state, kp.variants(), kp.answerSamples(),
                contentService.listDocsByKp(kp.id()), kp.scoringPoints(), activeFollowups(kp));
    }

    /** 挂载资料详情（C 端"看不懂"展开层）：只输出 available 内容（不审不上线）。 */
    public KbContentService.DocView doc(long customerId, long docId) {
        requireActiveProfile(customerId);
        KbContentService.DocView doc = contentService.getDoc(docId);
        if (!"available".equals(doc.status())) {
            throw BizException.notFound("资料讲解不存在");
        }
        return doc;
    }

    /** 开练习会话（R9）：任意 available kp 可练；active 档案校验（R11）；state 行按需 upsert 为 learning。 */
    @Transactional
    public PracticeSessionView start(long customerId, long kpId) {
        PrfProfile profile = requireActiveProfile(customerId);
        KpView kp = requireAvailableKp(kpId);
        String level = profile.getTargetLevel();
        if (level == null || level.isBlank()) {
            throw BizException.of(400, "LEARN_PROFILE_NOT_FINALIZED", "档案缺少目标职级，请先完成定稿");
        }
        SessionView session = judgeService.createSession(kpId, level, profile.getId(), "customer");
        learnStateService.upsertLearning(profile.getId(), kpId, session.id());
        return toView(session, kp, profile.getId());
    }

    // —— 作答转发（每次先过归属校验） ——

    @Transactional
    public AnswerResp submitAnswer(long customerId, long sessionId, String followupId, String answer) {
        long profileId = requireActiveProfileId(customerId);
        judgeService.assertOwnedByProfile(sessionId, profileId);
        return judgeService.submitAnswer(sessionId, followupId, answer);
    }

    @Transactional
    public AnswerResp submitEmpty(long customerId, long sessionId, String followupId) {
        long profileId = requireActiveProfileId(customerId);
        judgeService.assertOwnedByProfile(sessionId, profileId);
        return judgeService.submitEmpty(sessionId, followupId);
    }

    @Transactional
    public JudgeService.AnswerView selfEval(long customerId, long sessionId, long answerId, String verdict) {
        long profileId = requireActiveProfileId(customerId);
        judgeService.assertOwnedByProfile(sessionId, profileId);
        return judgeService.selfEval(sessionId, answerId, verdict);
    }

    // —— 查询 ——

    public PracticeSessionView get(long customerId, long sessionId) {
        long profileId = requireActiveProfileId(customerId);
        judgeService.assertOwnedByProfile(sessionId, profileId);
        SessionView session = judgeService.get(sessionId);
        return toView(session, contentService.getKp(session.kpId()), profileId);
    }

    // —— 关闭与回写（R8） ——

    @Transactional
    public CloseResult close(long customerId, long sessionId) {
        long profileId = requireActiveProfileId(customerId);
        judgeService.assertOwnedByProfile(sessionId, profileId);
        SessionView session = judgeService.close(sessionId);
        String grade = session.grade();
        if (grade != null && (grade.equals("pass") || grade.equals("good") || grade.equals("excellent"))) {
            learnStateService.markPassed(profileId, session.kpId(), sessionId);
        } else {
            learnStateService.markLearning(profileId, session.kpId(), sessionId);
        }
        LearnState row = learnStateService.getOwned(profileId, session.kpId());
        String state = row == null ? LearnStatus.LEARNING.getCode() : row.getStatus();
        return new CloseResult(toView(session, contentService.getKp(session.kpId()), profileId), state);
    }

    // —— 内部 ——

    private PrfProfile requireActiveProfile(long customerId) {
        PrfProfile profile = profileService.getActiveByCustomer(customerId);
        if (profile == null) {
            throw BizException.of(400, "LEARN_PROFILE_NOT_FINALIZED", "还没有当前档案，先建档并定稿再开始学习");
        }
        return profile;
    }

    private long requireActiveProfileId(long customerId) {
        return requireActiveProfile(customerId).getId();
    }

    private KpView requireAvailableKp(long kpId) {
        KpView kp = contentService.getKp(kpId);
        if (!"available".equals(kp.status())) {
            throw BizException.of(400, "LEARN_KP_NOT_AVAILABLE", "该知识点尚未上线，无法练习");
        }
        return kp;
    }

    private List<KbContentService.Followup> activeFollowups(KpView kp) {
        return kp.followups() == null ? List.of()
                : kp.followups().stream().filter(f -> "active".equals(f.status())).toList();
    }

    private int followupLayers(KpView kp) {
        return activeFollowups(kp).stream().mapToInt(KbContentService.Followup::layer).max().orElse(0);
    }

    /** 停止点快照（与 JudgeService.resolveStopLayer 同口径）：职级规则取目标档 stop_layer，未标定取最大层。 */
    private Integer stopLayerOf(KpView kp, String level) {
        if (kp.levelRules() != null) {
            for (KbContentService.LevelRule rule : kp.levelRules()) {
                if (level != null && level.equals(rule.level()) && rule.stopLayer() != null) {
                    return rule.stopLayer();
                }
            }
        }
        int max = followupLayers(kp);
        return max == 0 ? 0 : max;
    }

    private PracticeSessionView toView(SessionView session, KpView kp, long profileId) {
        LearnState row = learnStateService.getOwned(profileId, kp.id());
        String state = row == null ? LearnStatus.LEARNING.getCode() : row.getStatus();
        return new PracticeSessionView(session.id(), kp.id(), kp.topicId(), kp.title(), kp.summary(),
                kp.difficulty(), kp.frameworkMd(), session.level(), session.stopLayer(), followupLayers(kp),
                session.status(), session.grade(), session.gradeNote(), session.degraded(), state,
                kp.variants(), kp.answerSamples(), contentService.listDocsByKp(kp.id()), kp.scoringPoints(),
                activeFollowups(kp), session.answers(), session.createdAt(), session.closedAt());
    }
}
