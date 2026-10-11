package com.zhiji.kb.api;

import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.ContentItemView;
import com.zhiji.kb.service.KbContentService.DocView;
import com.zhiji.kb.service.KbContentService.KpView;
import com.zhiji.kb.service.KbContentService.UpdateDocReq;
import com.zhiji.kb.service.KbContentService.UpdateKpReq;
import com.zhiji.kb.service.KbMergeService;
import com.zhiji.kb.service.KbMergeService.MergeResp;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.kb.service.KbTopicService.TopicView;
import com.zhiji.common.web.PageResult;
import com.zhiji.source.service.SourceAiService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标准知识库接口（B 端员工会话，/api/admin/kb）——知识库目录 + 内容生产台 + 合并页 + 向导直达模式。
 *
 * 页面结构（核心模块定义 5.2）：知识库目录（资产家底：目录树/内容编辑/组织调整/直达新建/搜索）、
 * 内容生产台（审核队列/同位管理/复核到期，接口在 KbReviewController）、新增向导（直达模式：
 * ai/* 通用端点 + direct-submit，向导与素材库共用同一套 AI 辅助能力，6.4）。
 */
@RestController
@RequestMapping("/api/admin/kb")
@Validated
public class KbAdminController {

    private final KbTopicService topicService;
    private final KbContentService contentService;
    private final KbMergeService mergeService;
    private final SourceAiService sourceAiService;

    public KbAdminController(KbTopicService topicService, KbContentService contentService,
            KbMergeService mergeService, SourceAiService sourceAiService) {
        this.topicService = topicService;
        this.contentService = contentService;
        this.mergeService = mergeService;
        this.sourceAiService = sourceAiService;
    }

    // —— 主题（组织结构：名字用户可见、可改名；调整即时生效于目录视图） ——

    @GetMapping("/topics")
    public List<TopicView> topics(@RequestParam(required = false) String domainCode) {
        return topicService.list(domainCode);
    }

    public record CreateTopicReq(@NotBlank(message = "领域必须人工点选") String domainCode,
            @NotBlank(message = "主题名不能为空") @Size(max = 60, message = "主题名长度 60 以内") String name) {
    }

    @PostMapping("/topics")
    public TopicView createTopic(@Validated @RequestBody CreateTopicReq req) {
        return topicService.create(req.domainCode(), req.name());
    }

    public record RenameTopicReq(@NotBlank(message = "主题名不能为空") @Size(max = 60, message = "主题名长度 60 以内")
    String name) {
    }

    @PutMapping("/topics/{id}")
    public TopicView renameTopic(@PathVariable long id, @Validated @RequestBody RenameTopicReq req) {
        return topicService.rename(id, req.name());
    }

    public record MergeTopicReq(long targetId) {
    }

    @PostMapping("/topics/{id}/merge")
    public TopicView mergeTopic(@PathVariable long id, @RequestBody MergeTopicReq req) {
        return topicService.merge(id, req.targetId());
    }

    public record MoveTopicReq(@NotBlank(message = "领域必须人工点选") String domainCode) {
    }

    @PostMapping("/topics/{id}/move")
    public TopicView moveTopic(@PathVariable long id, @Validated @RequestBody MoveTopicReq req) {
        return topicService.move(id, req.domainCode());
    }

    // —— 目录与内容 ——

    @GetMapping("/contents")
    public PageResult<ContentItemView> contents(@RequestParam long topicId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return contentService.listByTopicPage(topicId, type, status, page, pageSize);
    }

    @GetMapping("/peers")
    public List<ContentItemView> peers(@RequestParam long topicId, @RequestParam(required = false) String type) {
        return contentService.peers(topicId, type);
    }

    @GetMapping("/search")
    public PageResult<ContentItemView> search(@RequestParam String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return contentService.searchPage(keyword, type, page, pageSize);
    }

    @GetMapping("/kp/{id}")
    public KpView getKp(@PathVariable long id) {
        return contentService.getKp(id);
    }

    @PutMapping("/kp/{id}")
    public KpView updateKp(@PathVariable long id, @RequestBody UpdateKpReq req) {
        return contentService.updateKp(id, req);
    }

    @GetMapping("/doc/{id}")
    public DocView getDoc(@PathVariable long id) {
        return contentService.getDoc(id);
    }

    @PutMapping("/doc/{id}")
    public DocView updateDoc(@PathVariable long id, @RequestBody UpdateDocReq req) {
        return contentService.updateDoc(id, req);
    }

    @PostMapping("/kp/{id}/offline")
    public ContentItemView offlineKp(@PathVariable long id) {
        return contentService.offline("kp", id);
    }

    @PostMapping("/kp/{id}/restore")
    public ContentItemView restoreKp(@PathVariable long id) {
        return contentService.restore("kp", id);
    }

    @PostMapping("/doc/{id}/offline")
    public ContentItemView offlineDoc(@PathVariable long id) {
        return contentService.offline("doc", id);
    }

    @PostMapping("/doc/{id}/restore")
    public ContentItemView restoreDoc(@PathVariable long id) {
        return contentService.restore("doc", id);
    }

    // —— 直达新建（5.2：成熟内容走向导第 1 步直接写成品，跳过拆分补概述与分类，不豁免概述门槛） ——

    public record DirectSubmitReq(@NotBlank(message = "定类必填") String contentType,
            @NotBlank(message = "领域必须人工点选") String domainCode, long topicId,
            @NotBlank(message = "标题不能为空") String title,
            @NotBlank(message = "概述齐备才能进入标准库") String summary,
            String contentMd, @NotBlank(message = "难度必须拍板") String difficulty) {
    }

    public record DirectSubmitResp(String contentType, long id) {
    }

    @PostMapping("/direct-submit")
    public DirectSubmitResp directSubmit(@Validated @RequestBody DirectSubmitReq req) {
        long id = contentService.createPending(req.contentType(), req.domainCode(), req.topicId(),
                req.title(), req.summary(), req.contentMd(), req.difficulty(), null, "direct", null);
        return new DirectSubmitResp(req.contentType(), id);
    }

    // —— 同位合并（最多三份三栏对照；AI 一键合并或人工参照手写；产物进审核队列） ——

    public record MergeIdsReq(List<Long> ids) {
    }

    @PostMapping("/merge/kp/validate")
    public List<KpView> validateKpMerge(@RequestBody MergeIdsReq req) {
        return mergeService.validateKp(req.ids());
    }

    @PostMapping("/merge/doc/validate")
    public List<DocView> validateDocMerge(@RequestBody MergeIdsReq req) {
        return mergeService.validateDoc(req.ids());
    }

    @PostMapping("/merge/ai")
    public MergeResp aiMerge(@RequestParam String type, @RequestBody MergeIdsReq req) {
        return mergeService.aiMerge(type, req.ids());
    }

    public record MergeSubmitReq(List<Long> ids, MergeResp product) {
    }

    @PostMapping("/merge/kp/submit")
    public ContentItemView submitKpMerge(@RequestBody MergeSubmitReq req) {
        return mergeService.submitKp(req.ids(), req.product());
    }

    @PostMapping("/merge/doc/submit")
    public ContentItemView submitDocMerge(@RequestBody MergeSubmitReq req) {
        return mergeService.submitDoc(req.ids(), req.product());
    }

    // —— 通用 AI 辅助（直达模式：内容不绑定素材行；委托 SourceAiService，与素材库同一套能力，6.4） ——

    public record AiContentReq(String contentMd) {
    }

    public record AiClassifyReq(String contentMd, @NotBlank(message = "领域必须人工点选") String domainCode,
            List<String> topics) {
    }

    @PostMapping("/ai/rewrite")
    public SourceAiService.RewriteResp aiRewrite(@RequestBody AiContentReq req) {
        return sourceAiService.rewrite(req.contentMd());
    }

    /** 直达模式流式改写（SSE）：不绑定素材行，逐片段推送；协议见 RewriteStreamSupport。 */
    @PostMapping(value = "/ai/rewrite-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter aiRewriteStream(@RequestBody AiContentReq req) {
        return com.zhiji.source.api.RewriteStreamSupport.stream(sourceAiService, req.contentMd());
    }

    @PostMapping("/ai/summary")
    public SourceAiService.SummaryResp aiSummary(@RequestBody AiContentReq req) {
        return new SourceAiService.SummaryResp(sourceAiService.summarize(req.contentMd()));
    }

    @PostMapping("/ai/classify")
    public SourceAiService.ClassifyResp aiClassify(@RequestBody AiClassifyReq req) {
        List<String> topics = req.topics() == null ? List.of() : req.topics();
        if (topics.isEmpty()) {
            return new SourceAiService.ClassifyResp("", "候选主题为空");
        }
        return sourceAiService.classify(req.contentMd(), req.domainCode(), topics);
    }

    @PostMapping("/ai/difficulty")
    public SourceAiService.DifficultyResp aiDifficulty(@RequestBody AiContentReq req) {
        return sourceAiService.difficulty(req.contentMd());
    }
}
