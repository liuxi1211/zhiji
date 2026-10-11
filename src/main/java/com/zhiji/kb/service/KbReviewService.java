package com.zhiji.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.KbDoc;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.domain.KbReview;
import com.zhiji.kb.repo.KbDocMapper;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.repo.KbTopicMapper;
import com.zhiji.kb.service.KbContentService.ContentItemView;
import com.zhiji.kb.service.KbContentService.DocView;
import com.zhiji.kb.service.KbContentService.KpView;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 审核服务（核心模块定义 5.2 进门质量门）：新增向导提交与同位合并重写的产物都先进审核队列，
 * 审核通过才转为可用（available）并对用户可见；审核不通过退回处理或废弃，留痕可回溯。
 *
 * 四项强制校验：技术正确性、评分要点可判定性（仅面试题线）、侵权检查、内容质量。
 * 一期人工为主，放行决定始终在人（预审只做分级提示属一期后置项）。
 */
@Service
public class KbReviewService {

    /** 四项强制校验：correctness/judgeable/infringement/quality 各 pass|fail|na；judgeable 文章线为 na。 */
    public record ReviewChecks(String correctness, String judgeable, String infringement, String quality,
            String notes) {
    }

    public record ReviewDetail(String contentType, KpView kp, DocView doc, List<KbReview> history) {
    }

    private final KbKpMapper kpMapper;
    private final KbDocMapper docMapper;
    private final KbTopicMapper topicMapper;
    private final KbContentService contentService;
    private final JsonMapper jsonMapper = new JsonMapper();

    public KbReviewService(KbKpMapper kpMapper, KbDocMapper docMapper, KbTopicMapper topicMapper,
            KbContentService contentService) {
        this.kpMapper = kpMapper;
        this.docMapper = docMapper;
        this.topicMapper = topicMapper;
        this.contentService = contentService;
    }

    /** 待办队列：pending 内容按入队先后排（打开的理由是"有事要做"，按待办量组织）。 */
    public List<ContentItemView> queue(String contentType) {
        List<ContentItemView> items = new ArrayList<>();
        if (contentType == null || contentType.isBlank() || "kp".equals(contentType)) {
            for (KbKp kp : kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                    .eq(KbKp::getStatus, "pending").orderByAsc(KbKp::getCreatedAt))) {
                items.add(item("kp", kp));
            }
        }
        if (contentType == null || contentType.isBlank() || "doc".equals(contentType)) {
            for (KbDoc doc : docMapper.selectList(new LambdaQueryWrapper<KbDoc>()
                    .eq(KbDoc::getStatus, "pending").orderByAsc(KbDoc::getCreatedAt))) {
                items.add(item("doc", doc));
            }
        }
        return items;
    }

    /** 审核详情：内容全量（用户侧预览所见即所得）+ 审核留痕历史。 */
    public ReviewDetail detail(String contentType, long id) {
        if ("kp".equals(contentType)) {
            KpView kp = contentService.getKp(id);
            return new ReviewDetail(contentType, kp, null, contentService.reviewsOf(contentType, id));
        }
        DocView doc = contentService.getDoc(id);
        return new ReviewDetail(contentType, null, doc, contentService.reviewsOf(contentType, id));
    }

    /**
     * 放行：四项全过才可（评分要点可判定性对面试题线硬要求，文章线 na）；
     * 通过转 available 并设置复核到期时间；合并产物据此把源标 merged、覆盖度转入。
     */
    @Transactional
    public ContentItemView pass(String contentType, long id, ReviewChecks checks, String comment, Integer dueDays) {
        requirePending(contentType, id);
        requireChecks(contentType, checks);
        int days = dueDays == null || dueDays <= 0 ? 90 : Math.min(dueDays, 365);
        String dueAt = KbContentService.dueDate(days);
        contentService.markStatus(contentType, id, "available", dueAt);
        contentService.insertReview(contentType, id, "pass", "wizard",
                jsonMapper.writeValueAsString(checks), comment);
        contentService.applyMergeOnPass(contentType, id);
        return itemView(contentType, id);
    }

    /** 退回：pending → draft，留意见；draft 在目录中可见，补正后重新提交审核。 */
    @Transactional
    public ContentItemView reject(String contentType, long id, String comment) {
        requirePending(contentType, id);
        if (comment == null || comment.isBlank()) {
            throw BizException.validation("退回必须给出审核意见");
        }
        contentService.markStatus(contentType, id, "draft", null);
        contentService.insertReview(contentType, id, "reject", "wizard", null, comment.strip());
        return itemView(contentType, id);
    }

    /** 废弃：pending → deprecated（不校不上线的另一半：坏内容不进库）。 */
    @Transactional
    public ContentItemView discard(String contentType, long id, String comment) {
        requirePending(contentType, id);
        contentService.markStatus(contentType, id, "deprecated", null);
        contentService.insertReview(contentType, id, "discard", "wizard", null,
                comment == null || comment.isBlank() ? null : comment.strip());
        return itemView(contentType, id);
    }

    /** 重新提交审核（退回的 draft 补正后走这里，重走进门质量门）。 */
    @Transactional
    public ContentItemView resubmit(String contentType, long id) {
        requireStatus(contentType, id, "draft", "内容不在草稿态，无法重新提交审核");
        contentService.markStatus(contentType, id, "pending", null);
        contentService.insertReview(contentType, id, "resubmit", "wizard", null, null);
        return itemView(contentType, id);
    }

    /** 复核到期列表：available 且 review_due_at <= 今天（一期列表筛选人工巡检，不自动扫描）。 */
    public List<ContentItemView> due() {
        String today = KbContentService.today();
        List<ContentItemView> items = new ArrayList<>();
        for (KbKp kp : kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                .eq(KbKp::getStatus, "available")
                .isNotNull(KbKp::getReviewDueAt)
                .le(KbKp::getReviewDueAt, today)
                .orderByAsc(KbKp::getReviewDueAt))) {
            items.add(item("kp", kp));
        }
        for (KbDoc doc : docMapper.selectList(new LambdaQueryWrapper<KbDoc>()
                .eq(KbDoc::getStatus, "available")
                .isNotNull(KbDoc::getReviewDueAt)
                .le(KbDoc::getReviewDueAt, today)
                .orderByAsc(KbDoc::getReviewDueAt))) {
            items.add(item("doc", doc));
        }
        return items;
    }

    /** 送复审：available → pending，重新回审核队列复检（留痕 action=recheck）。 */
    @Transactional
    public ContentItemView recheck(String contentType, long id) {
        requireStatus(contentType, id, "available", "只有已上线内容可以送复审");
        contentService.markStatus(contentType, id, "pending", null);
        contentService.insertReview(contentType, id, "recheck", "recheck", null, null);
        return itemView(contentType, id);
    }

    private void requireChecks(String contentType, ReviewChecks checks) {
        if (checks == null) {
            throw BizException.validation("四项强制校验必须逐项确认");
        }
        boolean kp = "kp".equals(contentType);
        if (!"pass".equals(checks.correctness()) || !"pass".equals(checks.infringement())
                || !"pass".equals(checks.quality())) {
            throw BizException.validation("存在未通过项，不能放行");
        }
        if (kp && !"pass".equals(checks.judgeable())) {
            throw BizException.validation("评分要点可判定性必须确认通过（面试题线）");
        }
        if (!kp && checks.judgeable() != null && !"na".equals(checks.judgeable())) {
            throw BizException.validation("资料讲解无评分要求，可判定性应标记 na");
        }
    }

    private void requirePending(String contentType, long id) {
        requireStatus(contentType, id, "pending", "内容不在审核队列中");
    }

    private void requireStatus(String contentType, long id, String expected, String message) {
        String status = "kp".equals(contentType)
                ? contentService.requireKp(id).getStatus()
                : contentService.requireDoc(id).getStatus();
        if (!expected.equals(status)) {
            throw BizException.validation(message);
        }
    }

    private ContentItemView itemView(String contentType, long id) {
        if ("kp".equals(contentType)) {
            return item("kp", contentService.requireKp(id));
        }
        return item("doc", contentService.requireDoc(id));
    }

    private ContentItemView item(String contentType, KbKp kp) {
        String topicName = topicName(kp.getTopicId());
        return new ContentItemView(contentType, kp.getId(), kp.getDomainCode(), kp.getTopicId(), topicName,
                kp.getTitle(), kp.getSummary(), kp.getDifficulty(), kp.getStatus(), null, kp.getMergedIntoId(),
                kp.getMergeSources() != null && !kp.getMergeSources().isBlank(),
                kp.getReviewDueAt(), kp.getCreatedAt(), kp.getUpdatedAt());
    }

    private ContentItemView item(String contentType, KbDoc doc) {
        String topicName = topicName(doc.getTopicId());
        return new ContentItemView(contentType, doc.getId(), doc.getDomainCode(), doc.getTopicId(), topicName,
                doc.getTitle(), doc.getSummary(), doc.getDifficulty(), doc.getStatus(), doc.getKpId(),
                doc.getMergedIntoId(), doc.getMergeSources() != null && !doc.getMergeSources().isBlank(),
                doc.getReviewDueAt(), doc.getCreatedAt(), doc.getUpdatedAt());
    }

    private String topicName(long topicId) {
        var topic = topicMapper.selectById(topicId);
        return topic == null ? "" : topic.getName();
    }
}
