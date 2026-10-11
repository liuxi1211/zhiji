package com.zhiji.source.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 领域内主题候选种子（AI 分类建议的边界）。
 * code 约定 "{领域}.{主题}"：SourceService.topicCandidates 按 domainCode 前缀过滤出候选主题。
 * 一期主题未建表，T4 标准库落地后主题清单以表为准，本组退化为种子数据。
 */
@ConstantGroup("domain_topics")
public enum DomainTopic implements ConstantItem {

    JAVA_JVM("java.jvm", "JVM"),
    JAVA_CONCURRENCY("java.concurrent", "并发编程"),
    JAVA_SPRING("java.spring", "Spring 生态"),
    JAVA_COLLECTION("java.collection", "集合框架"),
    DB_MYSQL("database.mysql", "MySQL"),
    DB_REDIS("database.redis", "Redis"),
    DB_MONGO("database.mongodb", "MongoDB"),
    DB_SHARDING("database.sharding", "分库分表"),
    MW_MQ("middleware.mq", "消息队列"),
    MW_ES("middleware.es", "Elasticsearch"),
    DIST_TRANSACTION("distributed.transaction", "分布式事务"),
    DIST_GOVERNANCE("distributed.governance", "服务治理"),
    DIST_DESIGN("distributed.design", "秒杀与系统设计"),
    CS_NETWORK("cs_basic.network", "网络"),
    CS_OS("cs_basic.os", "操作系统"),
    CS_ALGO("cs_basic.algo", "数据结构与算法"),
    GO_GOROUTINE("go.goroutine", "Goroutine 与调度"),
    GO_GMP("go.gmp", "GMP 模型"),
    ENG_CICD("engineering.cicd", "CI/CD"),
    ENG_INCIDENT("engineering.incident", "线上问题排查");

    private final String code;
    private final String name;

    DomainTopic(String code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }
}
