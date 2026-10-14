package com.zhiji.kb.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.kb.domain.KbTopic;
import com.zhiji.kb.repo.KbTopicMapper;
import com.zhiji.source.domain.DomainTopic;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 主题种子自举（核心模块定义 5.2/5.3）：kb_topic 空表时按 DomainTopic 常量组幂等种子；
 * 存量库描述补缺——种子枚举带 description 而表内同名主题无描述时回填（人工已编辑的描述不覆盖）。
 * T4 起主题清单以表为真相源，DomainTopic 常量组退化为种子数据——code 约定 "{领域}.{主题}"。
 */
@Component
public class KbBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbBootstrap.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KbTopicMapper topicMapper;

    public KbBootstrap(KbTopicMapper topicMapper) {
        this.topicMapper = topicMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, DomainTopic> seeds = Map.ofEntries(java.util.Arrays.stream(DomainTopic.values())
                .map(seed -> Map.entry(seed.getCode(), seed))
                .toArray(Map.Entry[]::new));
        if (topicMapper.selectCount(new LambdaQueryWrapper<>()) == 0) {
            insertSeeds(seeds);
            return;
        }
        backfillDescriptions(seeds);
    }

    /** 空表自举：按 DomainTopic 幂等种子（code 前缀为领域，名字与描述一并落库）。 */
    private void insertSeeds(Map<String, DomainTopic> seeds) {
        String now = LocalDateTime.now().format(TS);
        int count = 0;
        for (DomainTopic seed : seeds.values()) {
            String[] parts = seed.getCode().split("\\.", 2);
            if (parts.length != 2) {
                continue;
            }
            Long exists = topicMapper.selectCount(new LambdaQueryWrapper<KbTopic>()
                    .eq(KbTopic::getDomainCode, parts[0])
                    .eq(KbTopic::getName, seed.getName()));
            if (exists != null && exists > 0) {
                continue;
            }
            KbTopic topic = new KbTopic();
            topic.setDomainCode(parts[0]);
            topic.setName(seed.getName());
            topic.setDescription(seed.getDescription());
            topic.setStatus("active");
            topic.setCreatedAt(now);
            topic.setUpdatedAt(now);
            topicMapper.insert(topic);
            count++;
        }
        log.info("主题种子自举完成：插入 {} 条（来源 DomainTopic 常量组）", count);
    }

    /**
     * 存量库描述补缺："{领域}.{主题}" 命中表内同名主题且描述为空时回填种子描述；
     * 人工已写过的描述一律不动（只补缺，不覆盖）。
     */
    private void backfillDescriptions(Map<String, DomainTopic> seeds) {
        List<KbTopic> all = topicMapper.selectList(new LambdaQueryWrapper<>());
        // 表内主题按 "{domainCode}.{name}" 索引，与种子 code 对齐
        Map<String, KbTopic> indexed = all.stream()
                .collect(Collectors.toMap(t -> t.getDomainCode() + "." + t.getName(),
                        Function.identity(), (first, second) -> first));
        String now = LocalDateTime.now().format(TS);
        int filled = 0;
        for (Map.Entry<String, DomainTopic> entry : seeds.entrySet()) {
            KbTopic topic = indexed.get(entry.getKey());
            DomainTopic seed = entry.getValue();
            if (topic == null || (topic.getDescription() != null && !topic.getDescription().isBlank())) {
                continue;
            }
            topic.setDescription(seed.getDescription());
            topic.setUpdatedAt(now);
            topicMapper.updateById(topic);
            filled++;
        }
        if (filled > 0) {
            log.info("存量主题描述补缺：回填 {} 条（来源 DomainTopic 种子，只补空不覆盖）", filled);
        }
    }
}
