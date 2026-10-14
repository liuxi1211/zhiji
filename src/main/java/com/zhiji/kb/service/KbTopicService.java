package com.zhiji.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.constant.ConstantRegistry;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.KbDoc;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.domain.KbTopic;
import com.zhiji.kb.repo.KbDocMapper;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.repo.KbTopicMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主题服务（核心模块定义 5.2 组织结构线）：主题名字用户可见、可人工改名；
 * 人工可随时变更归类——新建、改名、调整主题的领域归属，调整即时生效于目录视图。
 * 单个内容的主题调整走 KbContentService.updateKp/updateDoc 的 topicId（限同领域）。
 * 组织结构调整只动"位置"（topic_id / domain_code），不动内容本身。
 */
@Service
public class KbTopicService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KbTopicMapper topicMapper;
    private final KbKpMapper kpMapper;
    private final KbDocMapper docMapper;
    private final ConstantRegistry constantRegistry;

    public KbTopicService(KbTopicMapper topicMapper, KbKpMapper kpMapper, KbDocMapper docMapper,
            ConstantRegistry constantRegistry) {
        this.topicMapper = topicMapper;
        this.kpMapper = kpMapper;
        this.docMapper = docMapper;
        this.constantRegistry = constantRegistry;
    }

    /** 主题视图：附内容计数，目录树一次取齐；description 为覆盖范围描述（可空）。 */
    public record TopicView(long id, String domainCode, String name, String description, String status,
            Long mergedIntoId, long kpCount, long docCount, String createdAt, String updatedAt) {
    }

    /** 主题摘要（AI 定位/分类注入用）：名字 + 覆盖范围描述（无描述退化为名字本身）。 */
    public record TopicBrief(String name, String description) {
    }

    /** 全库主题摘要（按领域分组键为 domainCode 的取数口径由调用方传入域 code 列表逐域拉取）。 */
    public List<TopicBrief> listBriefs(String domainCode) {
        requireDomain(domainCode);
        return topicMapper.selectList(new LambdaQueryWrapper<KbTopic>()
                        .eq(KbTopic::getDomainCode, domainCode)
                        .eq(KbTopic::getStatus, "active")
                        .orderByAsc(KbTopic::getId))
                .stream()
                .map(t -> new TopicBrief(t.getName(),
                        t.getDescription() == null || t.getDescription().isBlank() ? "" : t.getDescription().strip()))
                .toList();
    }

    public List<TopicView> list(String domainCode) {
        LambdaQueryWrapper<KbTopic> wrapper = new LambdaQueryWrapper<>();
        if (domainCode != null && !domainCode.isBlank()) {
            wrapper.eq(KbTopic::getDomainCode, domainCode);
        }
        wrapper.orderByAsc(KbTopic::getDomainCode).orderByAsc(KbTopic::getId);
        Map<Long, long[]> counts = contentCounts();
        return topicMapper.selectList(wrapper).stream().map(t -> toView(t, counts)).toList();
    }

    /** 领域内主题名候选（AI 分类建议的边界，5.1/5.2：只在所选领域内挑选）。 */
    public List<String> listNames(String domainCode) {
        requireDomain(domainCode);
        return topicMapper.selectList(new LambdaQueryWrapper<KbTopic>()
                        .eq(KbTopic::getDomainCode, domainCode)
                        .eq(KbTopic::getStatus, "active")
                        .orderByAsc(KbTopic::getId))
                .stream().map(KbTopic::getName).toList();
    }

    /** 描述规整：空白转 null，限长 200（AI 注入与人读提示都够用）。 */
    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.strip();
        if (trimmed.length() > 200) {
            throw BizException.validation("主题描述长度 200 以内");
        }
        return trimmed;
    }

    public TopicView create(String domainCode, String name, String description) {
        requireDomain(domainCode);
        String trimmed = requireName(name);
        if (topicMapper.selectCount(new LambdaQueryWrapper<KbTopic>()
                .eq(KbTopic::getDomainCode, domainCode).eq(KbTopic::getName, trimmed)) > 0) {
            throw BizException.conflict("KB_TOPIC_EXISTS", "该领域下已存在同名主题");
        }
        KbTopic topic = new KbTopic();
        topic.setDomainCode(domainCode);
        topic.setName(trimmed);
        topic.setDescription(normalizeDescription(description));
        topic.setStatus("active");
        String now = now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        topicMapper.insert(topic);
        return toView(topic, contentCounts());
    }

    /** 改名/改描述：主题名按"给用户看"的标准命名，描述补齐后 AI 定位更准（5.2 组织结构可改）。 */
    public TopicView rename(long id, String name, String description) {
        KbTopic topic = requireActive(id);
        String trimmed = requireName(name);
        Long duplicate = topicMapper.selectCount(new LambdaQueryWrapper<KbTopic>()
                .eq(KbTopic::getDomainCode, topic.getDomainCode())
                .eq(KbTopic::getName, trimmed)
                .ne(KbTopic::getId, id));
        if (duplicate != null && duplicate > 0) {
            throw BizException.conflict("KB_TOPIC_EXISTS", "该领域下���存在同名主题");
        }
        topic.setName(trimmed);
        topic.setDescription(normalizeDescription(description));
        topic.setUpdatedAt(now());
        topicMapper.updateById(topic);
        return toView(topic, contentCounts());
    }

    /**
     * 调整主题的领域归属：级联更新主题与其下内容的 domain_code（内容跟主题走）。
     * 主题领域变了，其下内容的领域归类同步生效——仍是"只动位置"。
     */
    @Transactional
    public TopicView move(long id, String domainCode) {
        requireDomain(domainCode);
        KbTopic topic = requireActive(id);
        if (topic.getDomainCode().equals(domainCode)) {
            return toView(topic, contentCounts());
        }
        String now = now();
        topic.setDomainCode(domainCode);
        topic.setUpdatedAt(now);
        topicMapper.updateById(topic);
        for (KbKp kp : kpMapper.selectList(new LambdaQueryWrapper<KbKp>().eq(KbKp::getTopicId, id))) {
            kp.setDomainCode(domainCode);
            kp.setUpdatedAt(now);
            kpMapper.updateById(kp);
        }
        for (KbDoc doc : docMapper.selectList(new LambdaQueryWrapper<KbDoc>().eq(KbDoc::getTopicId, id))) {
            doc.setDomainCode(domainCode);
            doc.setUpdatedAt(now);
            docMapper.updateById(doc);
        }
        return toView(topic, contentCounts());
    }

    public KbTopic requireActive(long id) {
        KbTopic topic = topicMapper.selectById(id);
        if (topic == null) {
            throw BizException.notFound("主题不存在");
        }
        if ("merged".equals(topic.getStatus())) {
            throw BizException.validation("主题已合并，请对合并目标主题操作");
        }
        return topic;
    }

    /** 领域必须落在固化清单内（5.3）：领域数量少、语义清晰是 AI 领域内分类精度的前提。 */
    public void requireDomain(String domainCode) {
        if (domainCode == null || domainCode.isBlank()) {
            throw BizException.validation("领域必须人工点选");
        }
        List<ConstantRegistry.Item> domains = constantRegistry.items("domain");
        if (domains != null && domains.stream().noneMatch(item -> item.code().equals(domainCode))) {
            throw BizException.validation("领域取值不在清单内");
        }
    }

    public Map<Long, long[]> contentCounts() {
        Map<Long, long[]> counts = new HashMap<>();
        for (KbKp kp : kpMapper.selectList(new LambdaQueryWrapper<>())) {
            long[] pair = counts.computeIfAbsent(kp.getTopicId(), k -> new long[2]);
            pair[0]++;
        }
        for (KbDoc doc : docMapper.selectList(new LambdaQueryWrapper<>())) {
            long[] pair = counts.computeIfAbsent(doc.getTopicId(), k -> new long[2]);
            pair[1]++;
        }
        return counts;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw BizException.validation("主题名不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > 60) {
            throw BizException.validation("主题名长度 60 以内");
        }
        return trimmed;
    }

    private TopicView toView(KbTopic topic, Map<Long, long[]> counts) {
        long[] pair = counts.getOrDefault(topic.getId(), new long[2]);
        return new TopicView(topic.getId(), topic.getDomainCode(), topic.getName(), topic.getDescription(),
                topic.getStatus(), topic.getMergedIntoId(), pair[0], pair[1], topic.getCreatedAt(),
                topic.getUpdatedAt());
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }
}
