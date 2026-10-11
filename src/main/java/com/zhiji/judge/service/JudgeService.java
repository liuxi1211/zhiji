package com.zhiji.judge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.judge.domain.Grade;
import com.zhiji.judge.domain.JudgeAnswer;
import com.zhiji.judge.domain.JudgeSession;
import com.zhiji.judge.repo.JudgeAnswerMapper;
import com.zhiji.judge.repo.JudgeSessionMapper;
import com.zhiji.judge.service.GradeCalculator.AnswerRow;
import com.zhiji.judge.service.GradeCalculator.GradeResult;
import com.zhiji.judge.service.GradeCalculator.PointHit;
import com.zhiji.judge.service.GradeCalculator.PointSpec;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.Followup;
import com.zhiji.kb.service.KbContentService.KpView;
import com.zhiji.kb.service.KbContentService.LevelRule;
import com.zhiji.kb.service.KbContentService.ScoringPoint;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 判定会话编排（核心模块定义 6.1，D4-D10）。
 *
 * 三条硬规则的落点：答况四分流由本类推导（模型只报事实，D1）；引用强制在 JudgeAiService（D2）；
 * 档位公式在 GradeCalculator（D3）。判定范围以创建时快照的停止点为界（D4），层号超过停止点的
 * 作答照常判定但不计入档位。降级路径：判定失败 → verdict=null + error_note，解锁自评（D7）。
 */
@Service
public class JudgeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> LEVELS = List.of("junior", "mid", "senior", "expert", "master");
    private static final List<String> VERDICTS = List.of("correct", "missing", "wrong", "empty");

    private final JudgeSessionMapper sessionMapper;
    private final JudgeAnswerMapper answerMapper;
    private final JudgeAiService aiService;
    private final KbContentService contentService;
    private final JsonMapper jsonMapper = new JsonMapper();

    public JudgeService(JudgeSessionMapper sessionMapper, JudgeAnswerMapper answerMapper,
            JudgeAiService aiService, KbContentService contentService) {
        this.sessionMapper = sessionMapper;
        this.answerMapper = answerMapper;
        this.aiService = aiService;
        this.contentService = contentService;
    }

    // —— 视图契约（与前端 types.ts 一一对应） ——

    /** 作答行视图。 */
    public record AnswerView(long id, String followupId, String questionText, int layer, String answerText,
            String verdict, String source, List<PointResultView> points, String wrongNote, String errorNote,
            String createdAt) {
    }

    /** 评分要点全量快照（含未命中项，6.1 配套机制）。 */
    public record PointResultView(String id, String level, String content, boolean hit, String quote) {
    }

    /** 会话视图：kp 题面 + 追问层 + 全量要点 + 停止点，供前端组织问答流（B 端质检工具可见尺子）。 */
    public record SessionView(long id, long kpId, String kpTitle, String kpSummary, String level,
            Integer stopLayer, String status, String grade, String gradeNote, boolean degraded,
            String createdAt, String closedAt,
            List<Followup> followups, List<ScoringPoint> scoringPoints, List<AnswerView> answers) {
    }

    /** 会话摘要（历史列表）。 */
    public record SessionItemView(long id, long kpId, String kpTitle, String level, Integer stopLayer,
            String status, String grade, boolean degraded, int answerCount, String createdAt, String closedAt) {
    }

    public record AnswerResp(AnswerView answer, boolean degraded, String advice) {
    }

    // —— 会话 ——

    @Transactional
    public SessionView createSession(long kpId, String level) {
        if (!LEVELS.contains(level)) {
            throw BizException.validation("目标职级取值不合法（junior/mid/senior/expert/master）");
        }
        KpView kp = contentService.getKp(kpId);
        if (!"available".equals(kp.status())) {
            throw BizException.validation("知识点不在可用状态（当前 " + kp.status() + "）：判定只对上线内容进行");
        }
        List<Followup> followups = kp.followups() == null ? List.of()
                : kp.followups().stream().filter(f -> "active".equals(f.status())).toList();
        JudgeSession session = new JudgeSession();
        session.setKpId(kpId);
        session.setLevel(level);
        session.setStopLayer(resolveStopLayer(kp, level, followups));
        session.setStatus("open");
        session.setGrade(null);
        session.setDegraded(0);
        session.setCreatedBy(requireStaffId());
        session.setCreatedAt(now());
        sessionMapper.insert(session);
        return toSessionView(session, kp, followups);
    }

    /** 停止点快照（D4）：职级规则里取目标档 stop_layer；未标定取最大 active 层。 */
    private Integer resolveStopLayer(KpView kp, String level, List<Followup> followups) {
        if (kp.levelRules() != null) {
            for (LevelRule rule : kp.levelRules()) {
                if (level.equals(rule.level()) && rule.stopLayer() != null) {
                    return rule.stopLayer();
                }
            }
        }
        return followups.stream().mapToInt(Followup::layer).max().orElse(0);
    }

    // —— 作答判定 ——

    @Transactional
    public AnswerResp submitAnswer(long sessionId, String followupId, String answer) {
        JudgeSession session = requireOpenSession(sessionId);
        KpView kp = contentService.getKp(session.getKpId());
        List<Followup> followups = kp.followups() == null ? List.of()
                : kp.followups().stream().filter(f -> "active".equals(f.status())).toList();

        String question;
        int layer;
        if (followupId == null || followupId.isBlank()) {
            question = kp.title();
            layer = 0;
        } else {
            Followup followup = followups.stream()
                    .filter(f -> followupId.equals(f.id()))
                    .findFirst()
                    .orElseThrow(() -> BizException.notFound("追问层不存在或已停用：" + followupId));
            question = followup.content();
            layer = followup.layer();
        }
        String trimmed = answer == null ? "" : answer.strip();
        if (trimmed.isEmpty()) {
            throw BizException.validation("作答内容为空：空答请走「未答」按钮，引擎单独处理");
        }

        JudgeAnswer row = new JudgeAnswer();
        row.setSessionId(sessionId);
        row.setFollowupId(followupId == null || followupId.isBlank() ? null : followupId);
        row.setQuestionText(question);
        row.setLayer(layer);
        row.setAnswerText(trimmed);
        row.setCreatedAt(now());
        try {
            JudgeAiService.AlignedJudgment aligned = aiService.align(question, trimmed, pointSpecs(kp));
            row.setVerdict(deriveVerdict(trimmed, aligned));
            row.setSource("llm");
            row.setPointsResult(toJson(pointResults(kp, aligned.points())));
            row.setWrongNote(aligned.wrong() ? aligned.wrongNote() : null);
        } catch (BizException e) {
            // 降级（6.1：判定永远不是单点故障）：verdict 留空 + error_note，解锁自评
            logFailure(sessionId, e);
            row.setVerdict(null);
            row.setSource("llm");
            row.setPointsResult(toJson(pointResults(kp, List.of())));
            row.setErrorNote("判定服务暂不可用（" + e.getCode() + "），请自评");
            answerMapper.insert(row);
            return new AnswerResp(toAnswerView(row), true, "判定失败已降级：请对该答况自评（对/缺/错/空）");
        }
        answerMapper.insert(row);
        return new AnswerResp(toAnswerView(row), false, advice(row.getVerdict(), inScope(layer, session.getStopLayer())));
    }

    /** 答况四分流（D1）：空答在上游短路；wrong 硬否决优先；有 pass 命中即 correct；否则 missing。 */
    private String deriveVerdict(String answer, JudgeAiService.AlignedJudgment aligned) {
        if (aligned.wrong()) {
            return "wrong";
        }
        boolean passHit = aligned.points().stream()
                .anyMatch(point -> point.hit() && "pass".equals(point.level()));
        if (passHit) {
            return "correct";
        }
        return "missing";
    }

    /** 空答短路（不调 LLM）：「空」是独立值（6.1 硬规则 1）。 */
    @Transactional
    public AnswerResp submitEmpty(long sessionId, String followupId) {
        JudgeSession session = requireOpenSession(sessionId);
        KpView kp = contentService.getKp(session.getKpId());
        String question;
        int layer;
        if (followupId == null || followupId.isBlank()) {
            question = kp.title();
            layer = 0;
        } else {
            Followup followup = activeFollowups(kp).stream()
                    .filter(f -> followupId.equals(f.id()))
                    .findFirst()
                    .orElseThrow(() -> BizException.notFound("追问层不存在或已停用：" + followupId));
            question = followup.content();
            layer = followup.layer();
        }
        JudgeAnswer row = new JudgeAnswer();
        row.setSessionId(sessionId);
        row.setFollowupId(followupId == null || followupId.isBlank() ? null : followupId);
        row.setQuestionText(question);
        row.setLayer(layer);
        row.setAnswerText("");
        row.setVerdict("empty");
        row.setSource("code");
        row.setPointsResult(toJson(pointResults(kp, List.of())));
        row.setCreatedAt(now());
        answerMapper.insert(row);
        return new AnswerResp(toAnswerView(row), false, advice("empty", inScope(layer, session.getStopLayer())));
    }

    /** 降级自评（D7）：仅 verdict 为空的行可自评且仅一次，计入档位并标记会话降级。 */
    @Transactional
    public AnswerView selfEval(long sessionId, long answerId, String verdict) {
        requireOpenSession(sessionId);
        if (!VERDICTS.contains(verdict)) {
            throw BizException.validation("自评答况取值不合法（correct/missing/wrong/empty）");
        }
        JudgeAnswer row = requireAnswer(answerId);
        if (row.getSessionId() != sessionId) {
            throw BizException.validation("作答行不属于该会话");
        }
        if (row.getVerdict() != null) {
            throw BizException.conflict("COMMON_CONFLICT", "该作答已有判定结论，不可自评");
        }
        row.setVerdict(verdict);
        row.setSource("self");
        answerMapper.updateById(row);
        JudgeSession session = requireSession(sessionId);
        session.setDegraded(1);
        sessionMapper.updateById(session);
        return toAnswerView(row);
    }

    // —— 关闭与档位 ——

    @Transactional
    public SessionView close(long sessionId) {
        JudgeSession session = requireOpenSession(sessionId);
        KpView kp = contentService.getKp(session.getKpId());
        List<JudgeAnswer> rows = answersOf(sessionId);
        if (rows.isEmpty()) {
            throw BizException.validation("会话还没有作答，先答至少一问再关闭");
        }
        if (rows.stream().noneMatch(r -> r.getLayer() != null && r.getLayer() == 0 && !"empty".equals(r.getVerdict()))) {
            throw BizException.validation("题面尚未作答，无法评级：先答题面再关闭");
        }
        GradeResult result = GradeCalculator.calculate(toAnswerRows(rows, session.getStopLayer()), pointSpecs(kp));
        session.setStatus("closed");
        session.setClosedAt(now());
        session.setGrade(result.grade() == null ? null : result.grade().code());
        session.setGradeNote(result.note());
        sessionMapper.updateById(session);
        return toSessionView(session, kp, activeFollowups(kp));
    }

    /** in-scope 过滤（D4）：层号超过停止点的作答照常判定但不计入档位。 */
    private List<AnswerRow> toAnswerRows(List<JudgeAnswer> rows, Integer stopLayer) {
        List<AnswerRow> result = new ArrayList<>();
        for (JudgeAnswer row : rows) {
            if (!inScope(row.getLayer() == null ? 0 : row.getLayer(), stopLayer)) {
                continue;
            }
            List<PointHit> hits = new ArrayList<>();
            for (PointResultView point : readPointResults(row.getPointsResult())) {
                hits.add(new PointHit(point.id(), point.level(), point.hit()));
            }
            result.add(new AnswerRow(row.getVerdict(), hits));
        }
        return result;
    }

    private boolean inScope(int layer, Integer stopLayer) {
        return stopLayer == null || layer <= stopLayer;
    }

    // —— 查询 ——

    public SessionView get(long sessionId) {
        JudgeSession session = requireSession(sessionId);
        KpView kp = contentService.getKp(session.getKpId());
        return toSessionView(session, kp, activeFollowups(kp));
    }

    public List<SessionItemView> list(String status, int page, int size) {
        LambdaQueryWrapper<JudgeSession> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(JudgeSession::getStatus, status);
        }
        wrapper.orderByDesc(JudgeSession::getId)
                .last("LIMIT " + Math.min(Math.max(size, 1), 100) + " OFFSET " + Math.max(page, 0) * size);
        List<JudgeSession> sessions = sessionMapper.selectList(wrapper);
        List<SessionItemView> items = new ArrayList<>();
        for (JudgeSession session : sessions) {
            Long count = answerMapper.selectCount(new LambdaQueryWrapper<JudgeAnswer>()
                    .eq(JudgeAnswer::getSessionId, session.getId()));
            items.add(new SessionItemView(session.getId(), session.getKpId(), kpTitle(session.getKpId()),
                    session.getLevel(), session.getStopLayer(), session.getStatus(), session.getGrade(),
                    session.getDegraded() != null && session.getDegraded() == 1,
                    count == null ? 0 : count.intValue(), session.getCreatedAt(), session.getClosedAt()));
        }
        return items;
    }

    // —— 内部 ——

    private List<PointSpec> pointSpecs(KpView kp) {
        return (kp.scoringPoints() == null ? List.<ScoringPoint>of() : kp.scoringPoints()).stream()
                .map(point -> new PointSpec(point.id(), point.level()))
                .toList();
    }

    /** 全量要点快照（含未命中项，D8）：命中的带引用原话，其余补齐为未命中，内容取自知识点当前值。 */
    private List<PointResultView> pointResults(KpView kp, List<JudgeAiService.AlignedPoint> points) {
        Map<String, JudgeAiService.AlignedPoint> pointById = new HashMap<>();
        for (JudgeAiService.AlignedPoint point : points) {
            pointById.put(point.id(), point);
        }
        List<PointResultView> complete = new ArrayList<>();
        for (ScoringPoint spec : kp.scoringPoints() == null ? List.<ScoringPoint>of() : kp.scoringPoints()) {
            JudgeAiService.AlignedPoint aligned = pointById.get(spec.id());
            complete.add(new PointResultView(spec.id(), spec.level(), spec.content(),
                    aligned != null && aligned.hit(), aligned == null ? "" : aligned.quote()));
        }
        return complete;
    }
    private String advice(String verdict, boolean inScope) {
        if (!inScope) {
            return "该层在停止点之外，判定不计入档位";
        }
        return switch (verdict) {
            case "correct" -> "答对：可下钻下一层";
            case "missing" -> "答缺：继续补充要点，或换角度再答";
            case "wrong" -> "答错：先看错误提示纠正，再同层换角度作答";
            case "empty" -> "未答：跳到下一层或回头补答";
            default -> "";
        };
    }

    private List<Followup> activeFollowups(KpView kp) {
        return kp.followups() == null ? List.of()
                : kp.followups().stream().filter(f -> "active".equals(f.status())).toList();
    }

    private SessionView toSessionView(JudgeSession session, KpView kp, List<Followup> followups) {
        List<AnswerView> answers = answersOf(session.getId()).stream().map(this::toAnswerView).toList();
        return new SessionView(session.getId(), kp.id(), kp.title(), kp.summary(), session.getLevel(),
                session.getStopLayer(), session.getStatus(), session.getGrade(), session.getGradeNote(),
                session.getDegraded() != null && session.getDegraded() == 1,
                session.getCreatedAt(), session.getClosedAt(), followups, kp.scoringPoints(), answers);
    }

    private AnswerView toAnswerView(JudgeAnswer row) {
        return new AnswerView(row.getId(), row.getFollowupId(), row.getQuestionText(),
                row.getLayer() == null ? 0 : row.getLayer(), row.getAnswerText(), row.getVerdict(),
                row.getSource(), readPointResults(row.getPointsResult()), row.getWrongNote(),
                row.getErrorNote(), row.getCreatedAt());
    }

    private List<PointResultView> readPointResults(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(jsonMapper.readValue(json, PointResultView[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<JudgeAnswer> answersOf(long sessionId) {
        return answerMapper.selectList(new LambdaQueryWrapper<JudgeAnswer>()
                .eq(JudgeAnswer::getSessionId, sessionId).orderByAsc(JudgeAnswer::getId));
    }

    private JudgeSession requireSession(long id) {
        JudgeSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw BizException.notFound("判定会话不存在：" + id);
        }
        return session;
    }

    private JudgeSession requireOpenSession(long id) {
        JudgeSession session = requireSession(id);
        if (!"open".equals(session.getStatus())) {
            throw BizException.validation("会话已关闭，不能继续作答");
        }
        return session;
    }

    private JudgeAnswer requireAnswer(long id) {
        JudgeAnswer row = answerMapper.selectById(id);
        if (row == null) {
            throw BizException.notFound("作答行不存在：" + id);
        }
        return row;
    }

    private String kpTitle(long kpId) {
        try {
            return contentService.getKp(kpId).title();
        } catch (BizException e) {
            return "（知识点已删除）";
        }
    }

    private void logFailure(long sessionId, BizException e) {
        // SYS_PROVIDER_ERROR / SYS_RATE_LIMITED 等按预期降级处理，不向上翻栈
        org.slf4j.LoggerFactory.getLogger(JudgeService.class)
                .info("判定调用失败进入降级（session={}）：{}", sessionId, e.getMessage());
    }

    private String toJson(Object value) {
        return jsonMapper.writeValueAsString(value == null ? List.of() : value);
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }

    private long requireStaffId() {
        Long staffId = AuthContext.staffId();
        if (staffId == null) {
            throw BizException.forbidden();
        }
        return staffId;
    }
}
