package com.zhiji.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.common.web.PageResult;
import com.zhiji.kb.domain.KbDoc;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.domain.KbReview;
import com.zhiji.kb.repo.KbDocMapper;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.repo.KbReviewMapper;
import com.zhiji.kb.repo.KbTopicMapper;
import com.zhiji.kb.domain.KbTopic;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 标准内容服务（核心模块定义 5.2）：知识点（面试题线）与资料讲解（文章线）的创建、编辑、
 * 目录组织、同位清单、模糊搜索、下线与恢复。
 *
 * 两条服务端红线：
 * - 追问层身份永久（6.2 红线 2）：保存时校验既有层 id 不可改不可删，只允许新增与停用；
 * - 下线全链路封死（5.2 边界）：deprecated/merged 的知识点不能作前置依赖、不能被资料讲解挂载、
 *   不能作合并目标（合并校验在 KbMergeService）。
 *
 * 入门即过审核（不校不上线）：createPending 一律落 pending + 审核留痕；
 * 人工编辑 available 直接生效不重审（知识库本体维护线，5.2）。
 */
@Service
public class KbContentService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final List<String> DIFFICULTIES = List.of("junior", "mid", "senior", "expert");
    private static final List<String> LEVELS = List.of("junior", "mid", "senior", "expert", "master");
    private static final List<String> GRADES = List.of("pass", "good", "excellent");

    private final KbKpMapper kpMapper;
    private final KbDocMapper docMapper;
    private final KbReviewMapper reviewMapper;
    private final KbTopicMapper topicMapper;
    private final JsonMapper jsonMapper = new JsonMapper();

    public KbContentService(KbKpMapper kpMapper, KbDocMapper docMapper, KbReviewMapper reviewMapper,
            KbTopicMapper topicMapper) {
        this.kpMapper = kpMapper;
        this.docMapper = docMapper;
        this.reviewMapper = reviewMapper;
        this.topicMapper = topicMapper;
    }

    // —— 结构化列的类型契约（与前端 types.ts 一一对应） ——

    public record Variant(String text) {
    }

    /** 追问层：id 经分配永不复用、永不修改（红线 2）；层的增减走新增/停用。 */
    public record Followup(String id, int layer, String direction, String content, String status) {
    }

    /** 分级评分要点：判定引擎的对齐尺子，level 为达到该要点贡献的档位。 */
    public record ScoringPoint(String id, String level, String content) {
    }

    /** 三档回答样例（及格/良好/优秀）。 */
    public record AnswerSample(String level, String content) {
    }

    /** 职级要求与停止点：required=目标职级要不要会，stopLayer=下钻停止点（层号）。 */
    public record LevelRule(String level, boolean required, Integer stopLayer) {
    }

    public record KpView(long id, String domainCode, long topicId, String topicName, String title, String summary,
            String frameworkMd, List<Variant> variants, List<Followup> followups,
            List<ScoringPoint> scoringPoints, List<AnswerSample> answerSamples, String difficulty,
            List<LevelRule> levelRules, List<Long> prerequisites,
            int freqManual, int freqSignal, int freqCoverage,
            String status, Long mergedIntoId, List<Long> mergeSources, String reviewDueAt,
            long createdBy, String createdAt, String updatedAt) {
    }

    public record DocView(long id, String domainCode, long topicId, String topicName, String title, String summary,
            String contentMd, String difficulty, String sourceUrl, Long kpId,
            String status, Long mergedIntoId, List<Long> mergeSources, String reviewDueAt,
            long createdBy, String createdAt, String updatedAt) {
    }

    /** 列表/搜索/同位共用的摘要视图：目录与同位管理始终有可读摘要。 */
    public record ContentItemView(String contentType, long id, String domainCode, long topicId, String topicName,
            String title, String summary, String difficulty, String status, Long kpId,
            Long mergedIntoId, boolean mergeProduct, String reviewDueAt, String createdAt, String updatedAt) {
    }

    // —— 创建（三个入口共用：向导提交 / 直达新建 / 合并产物） ——

    /**
     * 创建 pending 标准内容 + 审核留痕（进门先过审核门）。contentMd 为正文快照：
     * kp 落 framework_md，doc 落 content_md。sourceUrl 仅文章线溯源（D10）。
     */
    @Transactional
    public long createPending(String contentType, String domainCode, long topicId, String title, String summary,
            String body, String difficulty, String sourceUrl, String reviewSource, List<Long> mergeSources) {
        if (!"kp".equals(contentType) && !"doc".equals(contentType)) {
            throw BizException.validation("定类取值不合法：面试题线(kp)或文章线(doc)");
        }
        // 概述是入库门槛（5.1/5.2）：没有概述的内容不得进入标准库——保证目录与同位管理始终有可读摘要
        if (summary == null || summary.isBlank()) {
            throw BizException.validation("概述齐备才能进入标准库：请先补齐概述");
        }
        requireDifficulty(difficulty);
        requireTopicInDomain(topicId, domainCode);
        String trimmedTitle = requireTitle(title);
        String now = now();
        long contentId;
        if ("kp".equals(contentType)) {
            KbKp kp = new KbKp();
            kp.setDomainCode(domainCode);
            kp.setTopicId(topicId);
            kp.setTitle(trimmedTitle);
            kp.setSummary(trimSummary(summary));
            kp.setFrameworkMd(body == null ? "" : body);
            kp.setDifficulty(difficulty);
            kp.setFreqManual(0);
            kp.setFreqSignal(0);
            kp.setFreqCoverage(0);
            kp.setStatus("pending");
            kp.setMergeSources(toJson(mergeSources));
            kp.setCreatedBy(requireStaffId());
            kp.setCreatedAt(now);
            kp.setUpdatedAt(now);
            kpMapper.insert(kp);
            contentId = kp.getId();
        } else {
            KbDoc doc = new KbDoc();
            doc.setDomainCode(domainCode);
            doc.setTopicId(topicId);
            doc.setTitle(trimmedTitle);
            doc.setSummary(trimSummary(summary));
            doc.setContentMd(body == null ? "" : body);
            doc.setDifficulty(difficulty);
            doc.setSourceUrl(sourceUrl);
            doc.setStatus("pending");
            doc.setMergeSources(toJson(mergeSources));
            doc.setCreatedBy(requireStaffId());
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
            docMapper.insert(doc);
            contentId = doc.getId();
        }
        insertReview(contentType, contentId, "submit", reviewSource, null, null);
        return contentId;
    }

    // —— 查询 ——

    public KpView getKp(long id) {
        KbKp kp = requireKp(id);
        return toKpView(kp);
    }

    public DocView getDoc(long id) {
        KbDoc doc = requireDoc(id);
        return toDocView(doc);
    }

    /** 目录：主题下内容清单（可按类型与状态筛选，含已下线——资产家底要看得见全量）。 */
    public List<ContentItemView> listByTopic(long topicId, String contentType, String status) {
        String topicName = topicName(topicId);
        List<ContentItemView> items = new ArrayList<>();
        if (contentType == null || contentType.isBlank() || "kp".equals(contentType)) {
            LambdaQueryWrapper<KbKp> wrapper = new LambdaQueryWrapper<KbKp>().eq(KbKp::getTopicId, topicId);
            if (status != null && !status.isBlank()) {
                wrapper.eq(KbKp::getStatus, status);
            }
            wrapper.orderByDesc(KbKp::getUpdatedAt);
            kpMapper.selectList(wrapper).forEach(kp -> items.add(toItemView("kp", kp, topicName)));
        }
        if (contentType == null || contentType.isBlank() || "doc".equals(contentType)) {
            LambdaQueryWrapper<KbDoc> wrapper = new LambdaQueryWrapper<KbDoc>().eq(KbDoc::getTopicId, topicId);
            if (status != null && !status.isBlank()) {
                wrapper.eq(KbDoc::getStatus, status);
            }
            wrapper.orderByDesc(KbDoc::getUpdatedAt);
            docMapper.selectList(wrapper).forEach(doc -> items.add(toItemView("doc", doc, topicName)));
        }
        return items;
    }

    /** 目录分页：kp/doc 两表合并列表（各自 updatedAt 倒序），全量汇总后按页切片，total 为真实总数。 */
    public PageResult<ContentItemView> listByTopicPage(long topicId, String contentType, String status,
            int page, int pageSize) {
        List<ContentItemView> items = listByTopic(topicId, contentType, status);
        int p = PageResult.normalizePage(page);
        int size = PageResult.normalizePageSize(pageSize);
        return PageResult.of(PageResult.slice(items, p, size), items.size());
    }

    /** 同位清单（同位去重的工作面）：同主题下未下线内容，重复一眼可见，无需算法（5.2）。 */
    public List<ContentItemView> peers(long topicId, String contentType) {
        return listByTopic(topicId, contentType, null).stream()
                .filter(item -> !"deprecated".equals(item.status()) && !"merged".equals(item.status()))
                .toList();
    }

    /** 知识点模糊搜索（5.2 边界：本模块提供，服务复盘录题）：LIKE 题面/摘要/正文/变体。 */
    public List<ContentItemView> search(String keyword, String contentType) {
        if (keyword == null || keyword.isBlank()) {
            throw BizException.validation("搜索关键词不能为空");
        }
        String like = "%" + keyword.strip() + "%";
        List<ContentItemView> items = new ArrayList<>();
        if (contentType == null || contentType.isBlank() || "kp".equals(contentType)) {
            List<KbKp> hits = kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                    .and(w -> w.like(KbKp::getTitle, like)
                            .or().like(KbKp::getSummary, like)
                            .or().like(KbKp::getFrameworkMd, like)
                            .or().like(KbKp::getVariants, like))
                    .orderByDesc(KbKp::getUpdatedAt));
            hits.forEach(kp -> items.add(toItemView("kp", kp, topicName(kp.getTopicId()))));
        }
        if (contentType == null || contentType.isBlank() || "doc".equals(contentType)) {
            List<KbDoc> hits = docMapper.selectList(new LambdaQueryWrapper<KbDoc>()
                    .and(w -> w.like(KbDoc::getTitle, like)
                            .or().like(KbDoc::getSummary, like)
                            .or().like(KbDoc::getContentMd, like))
                    .orderByDesc(KbDoc::getUpdatedAt));
            hits.forEach(doc -> items.add(toItemView("doc", doc, topicName(doc.getTopicId()))));
        }
        return items;
    }

    /** 搜索分页：同一 LIKE 能力，kp/doc 合并列表按页切片（原先 LIMIT 50 的硬上限由分页取代）。 */
    public PageResult<ContentItemView> searchPage(String keyword, String contentType, int page, int pageSize) {
        List<ContentItemView> items = search(keyword, contentType);
        int p = PageResult.normalizePage(page);
        int size = PageResult.normalizePageSize(pageSize);
        return PageResult.of(PageResult.slice(items, p, size), items.size());
    }

    // —— 编辑（知识库本体维护线：available/pending/draft 可编辑，available 直接生效不重审） ——

    public record UpdateKpReq(String title, String summary, String frameworkMd, List<Variant> variants,
            List<Followup> followups, List<ScoringPoint> scoringPoints, List<AnswerSample> answerSamples,
            String difficulty, List<LevelRule> levelRules, List<Long> prerequisites,
            Integer freqManual, Long topicId) {
    }

    @Transactional
    public KpView updateKp(long id, UpdateKpReq req) {
        KbKp kp = requireEditableKp(id);
        if (req.title() != null) {
            kp.setTitle(requireTitle(req.title()));
        }
        if (req.summary() != null) {
            kp.setSummary(trimSummary(req.summary()));
        }
        if (req.frameworkMd() != null) {
            kp.setFrameworkMd(req.frameworkMd());
        }
        if (req.variants() != null) {
            kp.setVariants(toJson(req.variants()));
        }
        if (req.followups() != null) {
            guardFollowupIds(kp.getFollowups(), req.followups());
            kp.setFollowups(toJson(req.followups()));
        }
        if (req.scoringPoints() != null) {
            req.scoringPoints().forEach(point -> {
                if (point.level() == null || !GRADES.contains(point.level())) {
                    throw BizException.validation("评分要点档位取值不合法");
                }
            });
            kp.setScoringPoints(toJson(req.scoringPoints()));
        }
        if (req.answerSamples() != null) {
            req.answerSamples().forEach(sample -> {
                if (sample.level() == null || !GRADES.contains(sample.level())) {
                    throw BizException.validation("回答样例档位取值不合法");
                }
            });
            kp.setAnswerSamples(toJson(req.answerSamples()));
        }
        if (req.difficulty() != null) {
            requireDifficulty(req.difficulty());
            kp.setDifficulty(req.difficulty());
        }
        if (req.levelRules() != null) {
            req.levelRules().forEach(rule -> {
                if (rule.level() == null || !LEVELS.contains(rule.level())) {
                    throw BizException.validation("职级取值不在五档清单内");
                }
            });
            kp.setLevelRules(toJson(req.levelRules()));
        }
        if (req.prerequisites() != null) {
            kp.setPrerequisites(toJson(requirePrerequisites(id, req.prerequisites())));
        }
        if (req.freqManual() != null) {
            if (req.freqManual() < 0) {
                throw BizException.validation("频率标定不能为负数");
            }
            kp.setFreqManual(req.freqManual());
        }
        if (req.topicId() != null) {
            requireTopicInDomain(req.topicId(), kp.getDomainCode());
            kp.setTopicId(req.topicId());
        }
        kp.setUpdatedAt(now());
        kpMapper.updateById(kp);
        return toKpView(kp);
    }

    public record UpdateDocReq(String title, String summary, String contentMd, String difficulty,
            Long kpId, Long topicId) {
    }

    @Transactional
    public DocView updateDoc(long id, UpdateDocReq req) {
        KbDoc doc = requireEditableDoc(id);
        if (req.title() != null) {
            doc.setTitle(requireTitle(req.title()));
        }
        if (req.summary() != null) {
            doc.setSummary(trimSummary(req.summary()));
        }
        if (req.contentMd() != null) {
            doc.setContentMd(req.contentMd());
        }
        if (req.difficulty() != null) {
            requireDifficulty(req.difficulty());
            doc.setDifficulty(req.difficulty());
        }
        if (req.kpId() != null) {
            KbKp target = requireKp(req.kpId());
            if ("deprecated".equals(target.getStatus()) || "merged".equals(target.getStatus())) {
                throw BizException.validation("已下线知识点不能再作为挂载对象（下线全链路封死）");
            }
            doc.setKpId(req.kpId());
        }
        if (req.topicId() != null) {
            requireTopicInDomain(req.topicId(), doc.getDomainCode());
            doc.setTopicId(req.topicId());
        }
        doc.setUpdatedAt(now());
        docMapper.updateById(doc);
        return toDocView(doc);
    }

    // —— 下线与恢复（5.2：弃用直接下线；逻辑删除但数据保留，可恢复再利用） ——

    @Transactional
    public ContentItemView offline(String contentType, long id) {
        String now = now();
        if ("kp".equals(contentType)) {
            KbKp kp = requireKp(id);
            if (!"available".equals(kp.getStatus())) {
                throw BizException.validation("只有已上线内容可以弃用下线");
            }
            kp.setStatus("deprecated");
            kp.setUpdatedAt(now);
            kpMapper.updateById(kp);
            return toItemView("kp", kp, topicName(kp.getTopicId()));
        }
        KbDoc doc = requireDoc(id);
        if (!"available".equals(doc.getStatus())) {
            throw BizException.validation("只有已上线内容可以弃用下线");
        }
        doc.setStatus("deprecated");
        doc.setUpdatedAt(now);
        docMapper.updateById(doc);
        return toItemView("doc", doc, topicName(doc.getTopicId()));
    }

    /** 恢复：deprecated/merged → draft（数据保留可恢复），恢复后须重新提交审核。 */
    @Transactional
    public ContentItemView restore(String contentType, long id) {
        String now = now();
        if ("kp".equals(contentType)) {
            KbKp kp = requireKp(id);
            if (!"deprecated".equals(kp.getStatus()) && !"merged".equals(kp.getStatus())) {
                throw BizException.validation("只有已下线内容可以恢复");
            }
            kp.setStatus("draft");
            kp.setMergedIntoId(null);
            kp.setUpdatedAt(now);
            kpMapper.updateById(kp);
            return toItemView("kp", kp, topicName(kp.getTopicId()));
        }
        KbDoc doc = requireDoc(id);
        if (!"deprecated".equals(doc.getStatus()) && !"merged".equals(doc.getStatus())) {
            throw BizException.validation("只有已下线内容可以恢复");
        }
        doc.setStatus("draft");
        doc.setMergedIntoId(null);
        doc.setUpdatedAt(now);
        docMapper.updateById(doc);
        return toItemView("doc", doc, topicName(doc.getTopicId()));
    }

    // —— 审核流程的状态推进原语（KbReviewService 调用） ——

    @Transactional
    public void markStatus(String contentType, long id, String status, String reviewDueAt) {
        String now = now();
        if ("kp".equals(contentType)) {
            KbKp kp = requireKp(id);
            kp.setStatus(status);
            kp.setReviewDueAt(reviewDueAt);
            kp.setUpdatedAt(now);
            kpMapper.updateById(kp);
            return;
        }
        KbDoc doc = requireDoc(id);
        doc.setStatus(status);
        doc.setReviewDueAt(reviewDueAt);
        doc.setUpdatedAt(now);
        docMapper.updateById(doc);
    }

    /**
     * 审核放行时处理合并产物（D3/D9）：把 merge_sources 指向的源标 merged（merged_into 指向产物，
     * 数据保留可恢复），覆盖度频率由源转入产物——「新产物走审核通过后替换更新既有知识点（位置）」。
     */
    @Transactional
    public void applyMergeOnPass(String contentType, long productId) {
        List<Long> sourceIds = mergeSourcesOf(contentType, productId);
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        String now = now();
        if ("kp".equals(contentType)) {
            KbKp product = requireKp(productId);
            int coverage = product.getFreqCoverage() == null ? 0 : product.getFreqCoverage();
            for (Long sourceId : sourceIds) {
                if (sourceId == null || sourceId == productId) {
                    continue;
                }
                KbKp source = kpMapper.selectById(sourceId);
                if (source == null) {
                    continue;
                }
                if (source.getFreqManual() != null) {
                    coverage += source.getFreqManual();
                }
                if (source.getFreqSignal() != null) {
                    coverage += source.getFreqSignal();
                }
                if (source.getFreqCoverage() != null) {
                    coverage += source.getFreqCoverage();
                }
                source.setStatus("merged");
                source.setMergedIntoId(productId);
                source.setUpdatedAt(now);
                kpMapper.updateById(source);
            }
            product.setFreqCoverage(coverage);
            product.setUpdatedAt(now);
            kpMapper.updateById(product);
            return;
        }
        KbDoc product = requireDoc(productId);
        for (Long sourceId : sourceIds) {
            if (sourceId == null || sourceId == productId) {
                continue;
            }
            KbDoc source = docMapper.selectById(sourceId);
            if (source == null) {
                continue;
            }
            source.setStatus("merged");
            source.setMergedIntoId(productId);
            source.setUpdatedAt(now);
            docMapper.updateById(source);
        }
        product.setUpdatedAt(now);
        docMapper.updateById(product);
    }

    // —— 供 KbMergeService 与流程使用的查询原语 ——

    public KbKp requireKp(long id) {
        KbKp kp = kpMapper.selectById(id);
        if (kp == null) {
            throw BizException.notFound("知识点不存在");
        }
        return kp;
    }

    public KbDoc requireDoc(long id) {
        KbDoc doc = docMapper.selectById(id);
        if (doc == null) {
            throw BizException.notFound("资料讲解不存在");
        }
        return doc;
    }

    public List<Long> mergeSourcesOf(String contentType, long id) {
        String raw = "kp".equals(contentType) ? requireKp(id).getMergeSources() : requireDoc(id).getMergeSources();
        return raw == null || raw.isBlank() ? List.of() : fromJsonList(raw, Long.class);
    }

    public void insertReview(String contentType, long contentId, String action, String source, String checks,
            String comment) {
        KbReview review = new KbReview();
        review.setContentType(contentType);
        review.setContentId(contentId);
        review.setAction(action);
        review.setSource(source);
        review.setChecks(checks);
        review.setComment(comment);
        review.setOperatorId(requireStaffId());
        review.setCreatedAt(now());
        reviewMapper.insert(review);
    }

    public List<KbReview> reviewsOf(String contentType, long contentId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<KbReview>()
                .eq(KbReview::getContentType, contentType)
                .eq(KbReview::getContentId, contentId)
                .orderByAsc(KbReview::getId));
    }

    // —— 校验与工具 ——

    /** 红线 2 的服务端保证：既有追问层 id 不可改不可删，只允许追加新 id；status 可在 active/disabled 间切换。 */
    private void guardFollowupIds(String existingJson, List<Followup> incoming) {
        Set<String> existingIds = new LinkedHashSet<>();
        for (Followup f : fromJsonList(existingJson == null ? "[]" : existingJson, Followup.class)) {
            existingIds.add(f.id());
        }
        Set<String> seen = new HashSet<>();
        for (Followup f : incoming) {
            if (f.id() == null || f.id().isBlank()) {
                throw BizException.validation("追问层缺少稳定身份 id（红线 2）");
            }
            if (!seen.add(f.id())) {
                throw BizException.validation("追问层 id 重复");
            }
        }
        for (String existingId : existingIds) {
            if (!seen.contains(existingId)) {
                throw BizException.validation("追问层身份不可删除（红线 2），请改用停用");
            }
        }
    }

    /** 前置依赖指向非下线知识点，且不自引用（下线全链路封死，5.2 边界）。 */
    private List<Long> requirePrerequisites(long selfId, List<Long> prerequisites) {
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long prereq : prerequisites) {
            if (prereq == null) {
                continue;
            }
            if (prereq == selfId) {
                throw BizException.validation("前置依赖不能指向自身");
            }
            KbKp target = requireKp(prereq);
            if ("deprecated".equals(target.getStatus()) || "merged".equals(target.getStatus())) {
                throw BizException.validation("已下线知识点不能再作为前置依赖（下线全链路封死）");
            }
            distinct.add(prereq);
        }
        return List.copyOf(distinct);
    }

    private void requireTopicInDomain(long topicId, String domainCode) {
        KbTopic topic = topicMapper.selectById(topicId);
        if (topic == null) {
            throw BizException.notFound("主题不存在");
        }
        if ("merged".equals(topic.getStatus())) {
            throw BizException.validation("主题已合并，请改用合并目标主题");
        }
        if (!topic.getDomainCode().equals(domainCode)) {
            throw BizException.validation("主题不属于所选领域");
        }
    }

    private void requireDifficulty(String difficulty) {
        if (difficulty == null || !DIFFICULTIES.contains(difficulty)) {
            throw BizException.validation("难度取值不合法：初级/中级/高级/资深四档");
        }
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw BizException.validation("标题不能为空");
        }
        String trimmed = title.strip();
        if (trimmed.length() > 200) {
            throw BizException.validation("标题长度 200 以内");
        }
        return trimmed;
    }

    private String trimSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String trimmed = summary.strip();
        return trimmed.isEmpty() ? null : trimmed.substring(0, Math.min(trimmed.length(), 200));
    }

    private KbKp requireEditableKp(long id) {
        KbKp kp = requireKp(id);
        if ("deprecated".equals(kp.getStatus()) || "merged".equals(kp.getStatus())) {
            throw BizException.validation("已下线内容先恢复再编辑");
        }
        return kp;
    }

    private KbDoc requireEditableDoc(long id) {
        KbDoc doc = requireDoc(id);
        if ("deprecated".equals(doc.getStatus()) || "merged".equals(doc.getStatus())) {
            throw BizException.validation("已下线内容先恢复再编辑");
        }
        return doc;
    }

    private String topicName(long topicId) {
        KbTopic topic = topicMapper.selectById(topicId);
        return topic == null ? "" : topic.getName();
    }

    private KpView toKpView(KbKp kp) {
        return new KpView(kp.getId(), kp.getDomainCode(), kp.getTopicId(), topicName(kp.getTopicId()),
                kp.getTitle(), kp.getSummary(), kp.getFrameworkMd(),
                fromJsonList(kp.getVariants(), Variant.class),
                fromJsonList(kp.getFollowups(), Followup.class),
                fromJsonList(kp.getScoringPoints(), ScoringPoint.class),
                fromJsonList(kp.getAnswerSamples(), AnswerSample.class),
                kp.getDifficulty(),
                fromJsonList(kp.getLevelRules(), LevelRule.class),
                fromJsonList(kp.getPrerequisites(), Long.class),
                nz(kp.getFreqManual()), nz(kp.getFreqSignal()), nz(kp.getFreqCoverage()),
                kp.getStatus(), kp.getMergedIntoId(), mergeSourcesOf("kp", kp.getId()),
                kp.getReviewDueAt(), nzl(kp.getCreatedBy()), kp.getCreatedAt(), kp.getUpdatedAt());
    }

    private DocView toDocView(KbDoc doc) {
        return new DocView(doc.getId(), doc.getDomainCode(), doc.getTopicId(), topicName(doc.getTopicId()),
                doc.getTitle(), doc.getSummary(), doc.getContentMd(), doc.getDifficulty(), doc.getSourceUrl(),
                doc.getKpId(), doc.getStatus(), doc.getMergedIntoId(), mergeSourcesOf("doc", doc.getId()),
                doc.getReviewDueAt(), nzl(doc.getCreatedBy()), doc.getCreatedAt(), doc.getUpdatedAt());
    }

    private ContentItemView toItemView(String contentType, KbKp kp, String topicName) {
        return new ContentItemView(contentType, kp.getId(), kp.getDomainCode(), kp.getTopicId(), topicName,
                kp.getTitle(), kp.getSummary(), kp.getDifficulty(), kp.getStatus(), null,
                kp.getMergedIntoId(), kp.getMergeSources() != null && !kp.getMergeSources().isBlank(),
                kp.getReviewDueAt(), kp.getCreatedAt(), kp.getUpdatedAt());
    }

    private ContentItemView toItemView(String contentType, KbDoc doc, String topicName) {
        return new ContentItemView(contentType, doc.getId(), doc.getDomainCode(), doc.getTopicId(), topicName,
                doc.getTitle(), doc.getSummary(), doc.getDifficulty(), doc.getStatus(), doc.getKpId(),
                doc.getMergedIntoId(), doc.getMergeSources() != null && !doc.getMergeSources().isBlank(),
                doc.getReviewDueAt(), doc.getCreatedAt(), doc.getUpdatedAt());
    }

    private <T> List<T> fromJsonList(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return jsonMapper.readValue(json, jsonMapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        return jsonMapper.writeValueAsString(value);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private long nzl(Long value) {
        return value == null ? 0L : value;
    }

    private long requireStaffId() {
        Long staffId = AuthContext.staffId();
        if (staffId == null) {
            throw BizException.forbidden();
        }
        return staffId;
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }

    public static String today() {
        return LocalDateTime.now().format(DATE);
    }

    public static String dueDate(int days) {
        return LocalDateTime.now().plusDays(days).format(DATE);
    }
}
