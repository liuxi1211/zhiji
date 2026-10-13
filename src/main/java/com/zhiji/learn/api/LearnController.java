package com.zhiji.learn.api;

import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.judge.service.JudgeService.AnswerResp;
import com.zhiji.judge.service.JudgeService.AnswerView;
import com.zhiji.learn.domain.LearnState;
import com.zhiji.learn.service.LearnStateService;
import com.zhiji.learn.service.PathService;
import com.zhiji.learn.service.PathService.PathView;
import com.zhiji.learn.service.PracticeService;
import com.zhiji.learn.service.PracticeService.CloseResult;
import com.zhiji.learn.service.PracticeService.PracticeSessionView;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.service.ProfileService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习路径与练习接口（C 端客户会话，/api/learn，核心模块定义 4.2）。
 *
 * 学习路径（实时推导，不落表）→ 双节奏练习（速览=自评直接推进 / 练习=复用判定引擎逐层下钻），
 * 学习状态（learn_state 五态）为学习与复习（T12）共用的数据源。
 * 归属：全部端点取 AuthContext.customerId()；练习会话一律 customer 归属，越权 404（R7）。
 */
@RestController
@RequestMapping("/api/learn")
@Validated
public class LearnController {

    private final PathService pathService;
    private final PracticeService practiceService;
    private final LearnStateService learnStateService;
    private final ProfileService profileService;

    public LearnController(PathService pathService, PracticeService practiceService,
            LearnStateService learnStateService, ProfileService profileService) {
        this.pathService = pathService;
        this.practiceService = practiceService;
        this.learnStateService = learnStateService;
        this.profileService = profileService;
    }

    /** 速览/放弃/恢复的轻量返回体（前端据此更新单元进度）。 */
    public record LearnStateView(long kpId, String status, String progressedAt) {
    }

    // —— 学习路径 ——

    @GetMapping("/path")
    public PathView path() {
        return pathService.buildForCustomer(requireCustomer());
    }

    // —— 速览自评（quick / weak） ——

    public record QuickReq(@NotNull(message = "自评结论必填") Boolean known) {
    }

    @PostMapping("/kp/{kpId}/quick")
    public LearnStateView quick(@PathVariable long kpId, @Validated @RequestBody QuickReq req) {
        long profileId = requireActiveProfileId(requireCustomer());
        return toStateView(learnStateService.markQuick(profileId, kpId, req.known()));
    }

    // —— 放弃 / 恢复（4.2 唯一人工裁剪例外） ——

    public record AbandonReq(String reason) {
    }

    @PostMapping("/kp/{kpId}/abandon")
    public LearnStateView abandon(@PathVariable long kpId, @RequestBody(required = false) AbandonReq req) {
        long profileId = requireActiveProfileId(requireCustomer());
        return toStateView(learnStateService.abandon(profileId, kpId, req == null ? null : req.reason()));
    }

    @DeleteMapping("/kp/{kpId}/abandon")
    public LearnStateView restore(@PathVariable long kpId) {
        long profileId = requireActiveProfileId(requireCustomer());
        return toStateView(learnStateService.restore(profileId, kpId));
    }

    // —— 练习模式（复用判定引擎） ——

    /** 题面预览（选题后、选节奏前）：展示题面/变体/目标刻度/资料，不开判定会话。 */
    @GetMapping("/kp/{kpId}/brief")
    public PracticeService.PracticeBriefView brief(@PathVariable long kpId) {
        return practiceService.brief(requireCustomer(), kpId);
    }

    /** 挂载资料详情（"看不懂"展开层）：只回 available 内容。 */
    @GetMapping("/docs/{docId}")
    public com.zhiji.kb.service.KbContentService.DocView doc(@PathVariable long docId) {
        return practiceService.doc(requireCustomer(), docId);
    }

    @PostMapping("/kp/{kpId}/practice")
    public PracticeSessionView practice(@PathVariable long kpId) {
        return practiceService.start(requireCustomer(), kpId);
    }

    @GetMapping("/sessions/{id}")
    public PracticeSessionView session(@PathVariable long id) {
        return practiceService.get(requireCustomer(), id);
    }

    public record SubmitAnswerReq(String followupId, @NotBlank(message = "作答内容不能为空") String answer) {
    }

    @PostMapping("/sessions/{id}/answers")
    public AnswerResp answer(@PathVariable long id, @Validated @RequestBody SubmitAnswerReq req) {
        return practiceService.submitAnswer(requireCustomer(), id, req.followupId(), req.answer());
    }

    public record EmptyAnswerReq(String followupId) {
    }

    @PostMapping("/sessions/{id}/empty")
    public AnswerResp empty(@PathVariable long id, @Validated @RequestBody EmptyAnswerReq req) {
        return practiceService.submitEmpty(requireCustomer(), id, req.followupId());
    }

    public record SelfEvalReq(@NotNull(message = "作答行必填") Long answerId,
            @NotBlank(message = "自评答况必填") String verdict) {
    }

    @PostMapping("/sessions/{id}/self-eval")
    public AnswerView selfEval(@PathVariable long id, @Validated @RequestBody SelfEvalReq req) {
        return practiceService.selfEval(requireCustomer(), id, req.answerId(), req.verdict());
    }

    @PostMapping("/sessions/{id}/close")
    public CloseResult close(@PathVariable long id) {
        return practiceService.close(requireCustomer(), id);
    }

    // —— 内部 ——

    private LearnStateView toStateView(LearnState row) {
        return new LearnStateView(row.getKpId(), row.getStatus(), row.getProgressedAt());
    }

    private long requireCustomer() {
        Long customerId = AuthContext.customerId();
        if (customerId == null) {
            throw BizException.unauthorizedToken();
        }
        return customerId;
    }

    /** 速览/放弃面向当前档案：无当前档案按未定稿引导（与 PathService 同口径，R11）。 */
    private long requireActiveProfileId(long customerId) {
        PrfProfile profile = profileService.getActiveByCustomer(customerId);
        if (profile == null) {
            throw BizException.of(400, "LEARN_PROFILE_NOT_FINALIZED", "还没有当前档案，先建档并定稿再开始学习");
        }
        return profile.getId();
    }
}
