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
 * 人工可随时变更归类——新建、改名、合并主题、调整主题的领域归属，调整即时生效于目录视图。
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

    /** 主题视图：附内容计数，目录树一次取齐。 */
    public record TopicView(long id, String domainCode, String name, String status, Long mergedIntoId,
            long kpCount, long docCount, String createdAt, String updatedAt) {
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

    public TopicView create(String domainCode, String name) {
        requireDomain(domainCode);
        String trimmed = requireName(name);
        if (topicMapper.selectCount(new LambdaQueryWrapper<KbTopic>()
                .eq(KbTopic::getDomainCode, domainCode).eq(KbTopic::getName, trimmed)) > 0) {
            throw BizException.conflict("KB_TOPIC_EXISTS", "该领域下已存在同名主题");
        }
        KbTopic topic = new KbTopic();
        topic.setDomainCode(domainCode);
        topic.setName(trimmed);
        topic.setStatus("active");
        String now = now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        topicMapper.insert(topic);
        return toView(topic, contentCounts());
    }

    /** 改名：主题名按"给用户看"的标准命名，支持人工随时改名（5.2）。 */
    public TopicView rename(long id, String name) {
        KbTopic topic = requireActive(id);
        String trimmed = requireName(name);
        Long duplicate = topicMapper.selectCount(new LambdaQueryWrapper<KbTopic>()
                .eq(KbTopic::getDomainCode, topic.getDomainCode())
                .eq(KbTopic::getName, trimmed)
                .ne(KbTopic::getId, id));
        if (duplicate != null && duplicate > 0) {
            throw BizException.conflict("KB_TOPIC_EXISTS", "该领域下已存在同名主题");
        }
        topic.setName(trimmed);
        topic.setUpdatedAt(now());
        topicMapper.updateById(topic);
        return toView(topic, contentCounts());
    }

    /**
     * 合并主题：其下内容全部迁入目标主题（同领域），本主题标 merged。
     * 只动位置不动内容；调整即时生效于目录视图。
     */
    @Transactional
    public TopicView merge(long id, long targetId) {
        if (id == targetId) {
            throw BizException.validation("不能把主题合并到自身");
        }
        KbTopic source = requireActive(id);
        KbTopic target = requireActive(targetId);
        if (!source.getDomainCode().equals(target.getDomainCode())) {
            throw BizException.validation("只能合并同一领域下的主题");
        }
        String now = now();
        for (KbKp kp : kpMapper.selectList(new LambdaQueryWrapper<KbKp>().eq(KbKp::getTopicId, id))) {
            kp.setTopicId(targetId);
            kp.setUpdatedAt(now);
            kpMapper.updateById(kp);
        }
        for (KbDoc doc : docMapper.selectList(new LambdaQueryWrapper<KbDoc>().eq(KbDoc::getTopicId, id))) {
            doc.setTopicId(targetId);
            doc.setUpdatedAt(now);
            docMapper.updateById(doc);
        }
        source.setStatus("merged");
        source.setMergedIntoId(targetId);
        source.setUpdatedAt(now);
        topicMapper.updateById(source);
        return toView(source, contentCounts());
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
        return new TopicView(topic.getId(), topic.getDomainCode(), topic.getName(), topic.getStatus(),
                topic.getMergedIntoId(), pair[0], pair[1], topic.getCreatedAt(), topic.getUpdatedAt());
    }

    private String now() {
        return LocalDateTime.now().format(TS);
    }
}
