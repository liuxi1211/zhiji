package com.zhiji.source.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 领域内主题候选种子（AI 定位的闭集锚点）。
 * code 约定 "{领域}.{主题}"：SourceService.topicCandidates 按 domainCode 前缀过滤出候选主题。
 * T4 起主题清单以 kb_topic 表为准，本组退化为种子数据（KbBootstrap 幂等种子 + 描述补缺）；
 * description 为覆盖范围一句话（人读提示 + AI 定位注入），限 200 字内。
 */
@ConstantGroup("domain_topics")
public enum DomainTopic implements ConstantItem {

    JAVA_JVM("java.jvm", "JVM", "内存结构、类加载、GC 算法与调优、JIT，JVM 层面的原理与排障"),
    JAVA_CONCURRENCY("java.concurrent", "并发编程", "线程与线程池、锁与 AQS、JUC 工具、并发容器、虚拟线程"),
    JAVA_SPRING("java.spring", "Spring 生态", "IoC/AOP 原理、Bean 生命周期、事务与传播、Spring Boot 自动配置、MVC"),
    JAVA_COLLECTION("java.collection", "集合框架", "List/Set/Map 实现与原理、HashMap 底层、并发集合"),
    DB_MYSQL("database.mysql", "MySQL", "索引与 B+ 树、事务与 MVCC、锁、日志体系、SQL 调优"),
    DB_REDIS("database.redis", "Redis", "数据结构与底层实现、缓存策略、持久化、分布式锁、集群与哨兵"),
    DB_MONGO("database.mongodb", "MongoDB", "文档模型、索引、副本集与分片、与关系型的取舍"),
    DB_SHARDING("database.sharding", "分库分表", "拆分策略、分片键选择、跨片查询、扩容与数据迁移（落地侧）"),
    MW_MQ("middleware.mq", "消息队列", "Kafka/RocketMQ/RabbitMQ 的模型、顺序/重复/堆积、可靠性投递"),
    MW_ES("middleware.es", "Elasticsearch", "倒排索引、分词、写入与查询链路、深分页与聚合"),
    DIST_TRANSACTION("distributed.transaction", "分布式事务", "2PC/TCC/本地消息表/最大努力通知，最终一致性方案"),
    DIST_GOVERNANCE("distributed.governance", "服务治理", "注册发现、熔断限流降级、链路追踪、配置中心"),
    DIST_DESIGN("distributed.design", "秒杀与系统设计", "高并发场景方案设计：秒杀、抢购、短链、 feed 流、榜单"),
    CS_NETWORK("cs_basic.network", "网络", "TCP/UDP、三次握手与挥手、HTTP/HTTPS、TLS、拥塞控制"),
    CS_OS("cs_basic.os", "操作系统", "进程线程、内存管理、IO 多路复用、零拷贝、页缓存"),
    CS_ALGO("cs_basic.algo", "数据结构与算法", "常见数据结构、排序查找、链表树图、LeetCode 高频题型"),
    GO_GOROUTINE("go.goroutine", "Goroutine 与调度", "goroutine 生命周期、channel、sync 包、内存模型"),
    GO_GMP("go.gmp", "GMP 模型", "G/M/S 调度器结构、抢占与偷任务、调度循环"),
    ENG_CICD("engineering.cicd", "CI/CD", "流水线、构建与发布、Docker/K8s 使用、灰度与回滚"),
    ENG_INCIDENT("engineering.incident", "线上问题排查", "CPU/内存/磁盘/网络排障思路、Arthas、日志与监控告警");

    private final String code;
    private final String name;
    private final String description;

    DomainTopic(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
