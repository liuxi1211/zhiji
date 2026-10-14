package com.zhiji.review.api;

import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.review.service.ReviewService;
import com.zhiji.review.service.ReviewService.DrawResp;
import com.zhiji.review.service.ReviewService.ReviewResultView;
import com.zhiji.review.service.ReviewService.ReviewTodayView;
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
 * 今日复习接口（C 端客户会话，/api/review，核心模块定义 4.3）。
 *
 * 今日清单（四路信号混合推送）+ 随机抽取（碎片时间，动态权重）+ 自评结果回写
 * （只更新复习参数与流水，不回写学习五态）。归属：全部端点取 AuthContext.customerId()；
 * 档案未定稿 400 LEARN_PROFILE_NOT_FINALIZED（与 learn 模块同口径，R11）。
 */
@RestController
@RequestMapping("/api/review")
@Validated
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 今日清单视图（items + stats + 日期 + 档案名/职级）。 */
    @GetMapping("/today")
    public ReviewTodayView today() {
        return reviewService.today(requireCustomer());
    }

    public record ReviewResultReq(@NotNull(message = "自评结论必填") Boolean known,
            String mode) {
    }

    /** 复习自评：body {known, mode} → 落流水并回写复习参数（progressed 行）。 */
    @PostMapping("/kp/{kpId}/result")
    public ReviewResultView result(@PathVariable long kpId, @Validated @RequestBody ReviewResultReq req) {
        String mode = req.mode() == null || req.mode().isBlank() ? "today" : req.mode();
        if (!"today".equals(mode) && !"draw".equals(mode)) {
            throw BizException.validation("mode 取值不合法（today/draw）");
        }
        return reviewService.result(requireCustomer(), kpId, req.known(), mode);
    }

    /** 随机抽取一题：query domainCode（按域集中复习）与 exclude（会话内已抽 kpId，逗号分隔）。 */
    @GetMapping("/draw")
    public DrawResp draw(@RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String exclude) {
        List<Long> excludeIds = exclude == null || exclude.isBlank()
                ? List.of()
                : java.util.Arrays.stream(exclude.split(","))
                        .map(String::strip).filter(s -> s.matches("\\d+"))
                        .map(Long::parseLong).toList();
        return reviewService.draw(requireCustomer(), domainCode, excludeIds);
    }

    private long requireCustomer() {
        Long customerId = AuthContext.customerId();
        if (customerId == null) {
            throw BizException.unauthorizedToken();
        }
        return customerId;
    }
}
