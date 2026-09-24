package com.zhiji.kb.api;

import com.zhiji.kb.service.KpService;
import com.zhiji.kb.service.KpService.ShareKpView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识卡分享公开接口（/api/share，无鉴权，基础架构PRD 3.3 权限矩阵既有登记）。
 * 红线（知识库PRD 7.5）：只输出 active 知识点的库侧内容，绝不含个人数据；零 LLM 调用。
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final KpService kpService;

    public ShareController(KpService kpService) {
        this.kpService = kpService;
    }

    @GetMapping("/kp/{kpId}")
    public ShareKpView kp(@PathVariable long kpId) {
        return kpService.shareDetail(kpId);
    }
}
