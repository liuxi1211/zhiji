package com.zhiji.kb.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.KbContentStatus;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.domain.KbTopic;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.repo.KbTopicMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识点检索的 C 端入口（客户会话，/api/kp）：轻量候选视图，只输出已上线（available）知识点。
 * 服务建档技能条锚点挑选（T10），后续同一能力服务复盘录题——
 * "知识点模糊搜索在本模块提供，同时服务 C 端侧消费"（核心模块定义 5.2）。
 * 轻量视图刻意不含评分要点/追问链/职级规则等教学侧字段，个人语境的完整展示走各业务模块。
 */
@RestController
@RequestMapping("/api/kp")
@Validated
public class KbCustomerController {

    private final KbKpMapper kpMapper;
    private final KbTopicMapper topicMapper;

    public KbCustomerController(KbKpMapper kpMapper, KbTopicMapper topicMapper) {
        this.kpMapper = kpMapper;
        this.topicMapper = topicMapper;
    }

    public record KpCandidateView(long id, String title, String summary, String difficulty,
            String domainCode, String topicName) {
    }

    @GetMapping("/search")
    public List<KpCandidateView> search(@RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        if (q == null || q.isBlank()) {
            throw BizException.validation("搜索关键词不能为空");
        }
        int size = Math.min(Math.max(limit, 1), 20);
        String like = "%" + q.strip() + "%";
        List<KbKp> hits = kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                .eq(KbKp::getStatus, KbContentStatus.AVAILABLE.getCode())
                .and(w -> w.like(KbKp::getTitle, like).or().like(KbKp::getSummary, like))
                .orderByDesc(KbKp::getFreqManual)
                .orderByDesc(KbKp::getUpdatedAt)
                .last("LIMIT " + size));
        Map<Long, KbTopic> topics = hits.isEmpty() ? Map.of()
                : topicMapper.selectBatchIds(
                        hits.stream().map(KbKp::getTopicId).distinct().toList()).stream()
                        .collect(Collectors.toMap(KbTopic::getId, Function.identity()));
        return hits.stream().map(kp -> new KpCandidateView(kp.getId(), kp.getTitle(), kp.getSummary(),
                kp.getDifficulty(), kp.getDomainCode(),
                topics.containsKey(kp.getTopicId()) ? topics.get(kp.getTopicId()).getName() : "")).toList();
    }
}
