package com.zhiji.judge.api;

import com.zhiji.judge.service.JudgeService;
import com.zhiji.judge.service.JudgeService.AnswerResp;
import com.zhiji.judge.service.JudgeService.AnswerView;
import com.zhiji.judge.service.JudgeService.SessionItemView;
import com.zhiji.judge.service.JudgeService.SessionView;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.ContentItemView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 判定引擎接口（B 端员工会话，/api/admin/judge）：判定会话 + 逐问作答 + 降级自评 + 关闭评级。
 * 试判台是内容维护者的质检工具——5.2 审核项「评分要点可判定性」与职级停止点标定的人工验证入口；
 * 判定契约与 C 端练习/模拟考共用（消费方模块接入时复用 service 层）。
 */
@RestController
@RequestMapping("/api/admin/judge")
@Validated
public class JudgeController {

    private final JudgeService judgeService;
    private final KbContentService contentService;

    public JudgeController(JudgeService judgeService, KbContentService contentService) {
        this.judgeService = judgeService;
        this.contentService = contentService;
    }

    // —— kp 选择器：仅 available 面试题线（判定只对上线内容进行，D10） ——

    @GetMapping("/kps")
    public List<ContentItemView> kps(@RequestParam String keyword) {
        return contentService.search(keyword, "kp").stream()
                .filter(item -> "available".equals(item.status()))
                .toList();
    }

    // —— 会话 ——

    public record CreateSessionReq(@NotNull(message = "知识点必填") Long kpId,
            @NotBlank(message = "目标职级必填") String level) {
    }

    @PostMapping("/sessions")
    public SessionView createSession(@Validated @RequestBody CreateSessionReq req) {
        return judgeService.createSession(req.kpId(), req.level());
    }

    @GetMapping("/sessions")
    public List<SessionItemView> sessions(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return judgeService.list(status, page, size);
    }

    @GetMapping("/sessions/{id}")
    public SessionView session(@PathVariable long id) {
        return judgeService.get(id);
    }

    @PostMapping("/sessions/{id}/close")
    public SessionView close(@PathVariable long id) {
        return judgeService.close(id);
    }

    // —— 作答判定 ——

    public record SubmitAnswerReq(String followupId, @NotBlank(message = "作答内容不能为空") String answer) {
    }

    @PostMapping("/sessions/{id}/answers")
    public AnswerResp answer(@PathVariable long id, @Validated @RequestBody SubmitAnswerReq req) {
        return judgeService.submitAnswer(id, req.followupId(), req.answer());
    }

    public record EmptyAnswerReq(String followupId) {
    }

    @PostMapping("/sessions/{id}/empty")
    public AnswerResp empty(@PathVariable long id, @Validated @RequestBody EmptyAnswerReq req) {
        return judgeService.submitEmpty(id, req.followupId());
    }

    public record SelfEvalReq(@NotNull(message = "作答行必填") Long answerId,
            @NotBlank(message = "自评答况必填") String verdict) {
    }

    @PostMapping("/sessions/{id}/self-eval")
    public AnswerView selfEval(@PathVariable long id, @Validated @RequestBody SelfEvalReq req) {
        return judgeService.selfEval(id, req.answerId(), req.verdict());
    }
}
