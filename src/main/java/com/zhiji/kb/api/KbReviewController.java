package com.zhiji.kb.api;

import com.zhiji.kb.service.KbContentService.ContentItemView;
import com.zhiji.kb.service.KbReviewService;
import com.zhiji.kb.service.KbReviewService.ReviewChecks;
import com.zhiji.kb.service.KbReviewService.ReviewDetail;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核接口（B 端员工会话，/api/admin/kb/reviews）——内容生产台的审核队列与复核到期队列。
 * 进门质量门（核心模块定义 5.2）：四项强制校验人工确认后放行；退回/废弃留痕可回溯；
 * 放行决定始终在人。{type} 取值 kp | doc（面试题线 / 文章线）。
 */
@RestController
@RequestMapping("/api/admin/kb/reviews")
public class KbReviewController {

    private final KbReviewService reviewService;

    public KbReviewController(KbReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/queue")
    public List<ContentItemView> queue(@RequestParam(required = false) String contentType) {
        return reviewService.queue(contentType);
    }

    @GetMapping("/due")
    public List<ContentItemView> due() {
        return reviewService.due();
    }

    @GetMapping("/{type}/{id}/detail")
    public ReviewDetail detail(@PathVariable String type, @PathVariable long id) {
        return reviewService.detail(type, id);
    }

    public record PassReq(ReviewChecks checks, String comment, Integer dueDays) {
    }

    @PostMapping("/{type}/{id}/pass")
    public ContentItemView pass(@PathVariable String type, @PathVariable long id, @RequestBody PassReq req) {
        return reviewService.pass(type, id, req.checks(), req.comment(), req.dueDays());
    }

    public record RejectReq(String comment) {
    }

    @PostMapping("/{type}/{id}/reject")
    public ContentItemView reject(@PathVariable String type, @PathVariable long id, @RequestBody RejectReq req) {
        return reviewService.reject(type, id, req.comment());
    }

    public record DiscardReq(String comment) {
    }

    @PostMapping("/{type}/{id}/discard")
    public ContentItemView discard(@PathVariable String type, @PathVariable long id,
            @RequestBody(required = false) DiscardReq req) {
        return reviewService.discard(type, id, req == null ? null : req.comment());
    }

    @PostMapping("/{type}/{id}/resubmit")
    public ContentItemView resubmit(@PathVariable String type, @PathVariable long id) {
        return reviewService.resubmit(type, id);
    }

    @PostMapping("/{type}/{id}/recheck")
    public ContentItemView recheck(@PathVariable String type, @PathVariable long id) {
        return reviewService.recheck(type, id);
    }
}
