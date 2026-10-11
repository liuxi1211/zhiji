package com.zhiji.kb.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.kb.domain.KbTopic;
import com.zhiji.kb.repo.KbTopicMapper;
import com.zhiji.source.domain.DomainTopic;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 主题种子自举（核心模块定义 5.2/5.3）：kb_topic 空表时按 DomainTopic 常量组幂等种子。
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
        if (topicMapper.selectCount(new LambdaQueryWrapper<>()) > 0) {
            return;
        }
        String now = LocalDateTime.now().format(TS);
        int count = 0;
        for (DomainTopic seed : DomainTopic.values()) {
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
            topic.setStatus("active");
            topic.setCreatedAt(now);
            topic.setUpdatedAt(now);
            topicMapper.insert(topic);
            count++;
        }
        log.info("主题种子自举完成：插入 {} 条（来源 DomainTopic 常量组）", count);
    }
}
