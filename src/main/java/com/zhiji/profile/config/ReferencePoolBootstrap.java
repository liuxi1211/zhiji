package com.zhiji.profile.config;

import com.zhiji.profile.domain.PrfReferenceClaim;
import com.zhiji.profile.repo.PrfReferenceClaimMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 参考技能条种子（PRD v2 5.4），两层结构：
 * <ul>
 *   <li>SEED：单条池 151 条（首批 Java 后端方向 + 2026-10 扩充的网络优质技能描述，按初级→资深分组），
 *       示范「每一条怎么写」；</li>
 *   <li>SECTION_SEED：整段样板 16 段（2026-10 从网络真实简历收集的完整技能部分，按
 *       junior/mid/senior/expert 四档打标，脱敏整理），示范「一组怎么配」。</li>
 * </ul>
 * 种子策略 = 按正文幂等增量补种：已入库条目不动、缺失条目追加，重启不重复、旧库可补新种子。
 * 生产运营在 B 端持续扩充。
 */
@Component
public class ReferencePoolBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferencePoolBootstrap.class);

    private final PrfReferenceClaimMapper mapper;

    public ReferencePoolBootstrap(PrfReferenceClaimMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 行数已齐（含人工后续新增）即视为种子完整，快速返回
        List<PrfReferenceClaim> existing = mapper.selectList(null);
        int totalSeeds = SEED.length + SECTION_SEED.length;
        if (existing.size() >= totalSeeds) {
            return;
        }
        // 按正文判重增量补种：已库条目不动，缺失条目追加在现有 sortOrder 之后
        Set<String> known = new HashSet<>();
        for (PrfReferenceClaim item : existing) {
            known.add(item.getContent());
        }
        int sort = existing.stream()
                .map(PrfReferenceClaim::getSortOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
        int added = 0;
        for (String[] item : SEED) {
            if (known.contains(item[1])) {
                continue;
            }
            insert("bar", "", item[0], item[1], sort++);
            added++;
        }
        for (String[] item : SECTION_SEED) {
            if (known.contains(item[2])) {
                continue;
            }
            insert("section", item[0], item[1], item[2], sort++);
            added++;
        }
        if (added > 0) {
            log.info("参考技能条池补种 {} 条（单条 {} + 整段样板 {}，java_backend，种子总量 {} 条）",
                    added, SEED.length, SECTION_SEED.length, totalSeeds);
        }
    }

    private void insert(String kind, String grade, String title, String content, int sort) {
        PrfReferenceClaim claim = new PrfReferenceClaim();
        claim.setDirection("java_backend");
        claim.setKind(kind);
        claim.setGrade(grade);
        claim.setTitle(title);
        claim.setContent(content);
        claim.setSortOrder(sort);
        claim.setEnabled(1);
        claim.setCreatedAt(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        mapper.insert(claim);
    }

    private static final String[][] SEED = {
        {"Java 语言与集合", "3 年 Java 后端研发经验，熟练使用 Spring Boot 构建业务服务，理解 IOC、AOP 与自动装配机制；掌握 JVM 内存模型与常用调优手段。"},
        {"Java 语言与集合", "扎实掌握 Java 集合框架原理：ArrayList 扩容、HashMap 的扰动函数与树化阈值、ConcurrentHashMap 的 CAS + synchronized 分段协调，能在选型时讲清各实现的取舍。"},
        {"Java 语言与集合", "熟悉 Java 8+ 特性在业务中的落地：Stream 管道处理批量数据、Optional 消除层级判空、CompletableFuture 编排并行调用，代码可读性与性能兼顾。"},
        {"Java 语言与集合", "理解 Java 泛型与反射机制，具备基于注解 + 反射的通用能力封装经验（如通用导出、数据脱敏），【讲不出 2 个真实案例就删本句】。"},
        {"并发编程", "掌握 Java 并发编程底层支撑：volatile 的内存可见性语义、synchronized 锁升级过程、AQS 的 CLH 队列与 state 设计，能据此定位线程安全问题。"},
        {"并发编程", "具备线程池线上定参经验：按任务类型区分 IO 密集与 CPU 密集设置核心参数，配合有界队列与拒绝策略治理突发流量，【线程池规模案例待补充】。"},
        {"并发编程", "熟悉 ThreadLocal 的内存泄漏成因与清理规范，在用户上下文传递、链路追踪场景有实践；理解虚拟线程的适用边界。"},
        {"并发编程", "熟练使用 ConcurrentHashMap、BlockingQueue 等并发容器，理解 CAS 的 ABA 问题与 Atomic 类的适用场景，能设计线程安全的缓存与计数方案。"},
        {"并发编程", "理解 JMM 的 happens-before 规则，能通过 jstack 分析死锁与线程堆积，【线上并发问题排查案例待补充】。"},
        {"JVM", "熟悉 JVM 内存区域划分与对象分配过程：Eden 与 Survivor 的流转、大对象直接进入老年代的条件、TLAB 的作用。"},
        {"JVM", "理解 G1 的 Region 化内存管理与混合回收流程，掌握常用 GC 参数含义，具备 CPU 飙高、频繁 Full GC 的排查经验（jstat / arthas / GC 日志）。"},
        {"JVM", "了解 ZGC 的着色指针与并发整理思路，能结合业务延迟要求给出回收器选型建议。"},
        {"JVM", "具备 OOM 问题治理经验：堆 dump 分析（MAT）、元空间与直接内存泄漏定位，【OOM 案例待补充】。"},
        {"框架与工程化", "熟练使用 Spring / Spring Boot，理解 Bean 生命周期、循环依赖的三级缓存解法、事务传播行为与失效场景；具备 starter 封装经验。"},
        {"框架与工程化", "理解 Spring AOP 的动态代理实现与切面执行顺序，用其实现日志埋点、幂等校验、限流注解等横切能力。"},
        {"框架与工程化", "熟悉 MyBatis 的缓存与插件机制，能通过自定义 TypeHandler、拦截器实现通用能力（慢 SQL 打点、数据权限）。"},
        {"框架与工程化", "具备基于设计模式的扩展点设计能力（策略 + 工厂、责任链、模板方法），【真实扩展点案例待补充】。"},
        {"框架与工程化", "熟悉 SpringCloud 微服务组件（只写真实用过的：Nacos / Gateway / OpenFeign / Sentinel），理解服务注册发现与配置热更新的原理。"},
        {"存储与 MySQL", "熟练使用 MySQL 进行表结构与索引设计，理解 B+ 树的页分裂与最左前缀原则，能结合 explain 输出定位慢查询并给出优化方案。"},
        {"存储与 MySQL", "理解 InnoDB 的事务实现：redo log 与 binlog 的两阶段提交、MVCC 的 ReadView 可见性判断、间隙锁与临键锁的加锁范围。"},
        {"存储与 MySQL", "具备分库分表实践经验：分片键选择、跨片查询与迁移方案，【分库分表规模案例待补充】。"},
        {"存储与 MySQL", "熟悉主从复制的线程模型与延迟成因，能设计读写分离下的读一致性方案（强制走主 / 延迟兜底）。"},
        {"存储与 MySQL", "理解 PolarDB 存算分离架构与选型依据，具备自建 MySQL 到云数据库的迁移与版本升级经验。【用过才写】"},
        {"存储与 MySQL", "掌握海量数据处理的分治思路：深分页优化（游标 / 延迟关联）、批量写入、归档与冷热分离。"},
        {"缓存与 Redis", "熟练使用 Redis 承担缓存场景，理解穿透 / 击穿 / 雪崩的成因与治理（布隆过滤器、互斥重建、多级过期）。"},
        {"缓存与 Redis", "理解 Redis 持久化机制：RDB 与 AOF 的取舍、混合持久化、主从复制中的 repl_backlog 作用。"},
        {"缓存与 Redis", "熟悉 Redis Cluster 的槽位与路由机制，具备大 key 治理（拆分 / 异步删除）与热 key 本地缓存兜底的实战经验。"},
        {"缓存与 Redis", "掌握缓存一致性方案取舍：Cache Aside 的先更新库再删缓存、延迟双删、基于 binlog 的订阅失效，【一致性问题案例待补充】。"},
        {"缓存与 Redis", "理解分布式锁的实现要点（setnx + 过期 + 看门狗续期、RedLock 争议），能结合业务选择锁粒度与可重入方案。"},
        {"消息队列", "使用 RocketMQ 承担异步解耦与削峰，理解消息幂等（业务唯一键 + 状态机）、顺序消息的队列选择与积压治理（扩容消费组 / 跳过策略）。"},
        {"消息队列", "理解 Kafka 的高吞吐设计：顺序写、零拷贝、ISR 与 ack 配置的可靠性取舍，具备消费者位移管理与再均衡调优经验。"},
        {"消息队列", "具备消息链路稳定性经验：生产者确认、消费端重试与死信队列、消息堆积的应急处理，【消息事故案例待补充】。"},
        {"消息队列", "理解事务消息的半消息机制，能用其解耦本地事务与下游通知，并清楚其最终一致性的边界。"},
        {"搜索与检索", "使用 Elasticsearch 承担检索场景，理解倒排索引与段结构、BM25 相关性打分、refresh / Translog / flush 的近实时写入链路。"},
        {"搜索与检索", "具备 ES 查询调优经验：深分页治理（search_after）、聚合精度与 cardinality 取舍、大查询限流熔断。"},
        {"搜索与检索", "熟悉分片规划与路由策略、索引别名与滚动重建，【集群规模案例待补充】。"},
        {"分布式与微服务", "理解分布式事务的模式取舍：TCC 的空回滚与悬挂、Saga 的补偿设计、本地消息表的落地成本，能按业务一致性要求选型。"},
        {"分布式与微服务", "熟悉分布式 ID 生成方案（号段、雪花及其时钟回拨处理），具备分布式锁、幂等框架的落地经验。"},
        {"分布式与微服务", "掌握限流熔断降级的层次：接入层限流、单机令牌桶 / 滑动窗口、熔断器的半开探测与降级预案设计。"},
        {"分布式与微服务", "理解主流中间件的数据分片、复制与共识选主机制（Raft），具备集群容量规划与故障演练经验。"},
        {"分布式与微服务", "熟悉服务治理实践：灰度发布、金丝雀验证、链路追踪（traceId 贯穿）、配置中心的热更新风险控制。"},
        {"分布式与微服务", "理解 CAP 与 BASE 的工程含义，能针对具体链路说明一致性级别选择与补偿方案。"},
        {"系统设计", "能独立完成高并发场景系统设计（秒杀 / 排行榜 / feed 流）：流量漏斗拆解、多级缓存与异步化、无状态水平扩容。"},
        {"系统设计", "具备接口性能治理经验：RT 拆解到 SQL / RPC / 序列化各环节，通过异步编排与并行调用压榨吞吐，【性能优化收益案例待补充】。"},
        {"系统设计", "理解幂等、重试、超时、隔离四大稳定性手段在跨服务调用中的组合应用，能设计可降级的依赖拓扑。"},
        {"工程与协作素养", "坚持数据驱动排障：全链路追踪定位耗时、metrics 看板发现异常、压测复现容量瓶颈，【线上紧急问题处置案例待补充】。"},
        {"工程与协作素养", "熟悉容器化部署与 K8s 排障：Pod 资源限制与 OOMKilled、探针配置、滚动发布与回滚。"},
        {"工程与协作素养", "负责核心模块方案设计与评审，坚持技术文档化，【沉淀文档数量待补充】。"},
        {"工程与协作素养", "具备新人带教与代码评审经验，推动团队编码规范与故障复盘机制落地。"},
        {"AI 研发效能", "实践 AI 辅助研发工作流：用 AI 完成代码生成 / 重构 / 测试补齐，并建立人工审核与回归验证的习惯。【工具与落地程度按实际填写】。"},
        {"AI 研发效能", "了解大模型应用开发的检索侧建设：chunking 策略、BM25 + 向量混合检索、rerank 精排，【用过才写：Spring AI / LangChain4j 集成经验】。"},

        // ---- 2026-10 扩充：网络优质简历技能描述 100 条（初级 → 资深，正文保留原貌仅规范排版） ----

        // ===== 初级 / 应届生（24 条）=====
        {"Java 语言与集合", "熟练使用 Java（掌握集合、多线程、IO 流等核心特性），理解面向对象编程思想，了解 Python 基础语法。"},
        {"框架与工程化", "掌握 Spring Boot、MyBatis-Plus，能快速搭建 Web 项目，理解 Bean 生命周期、AOP、参数校验和统一异常处理。"},
        {"存储与 MySQL", "熟悉 MySQL 的增删改查操作，了解索引优化、事务处理基本原理，能使用 Explain 分析简单慢 SQL。"},
        {"工程与协作素养", "熟练使用 Git、Maven、Docker、Postman，掌握 Linux 常用命令，能独立完成项目的构建、部署与接口调试。"},
        {"分布式与微服务", "熟悉 Nacos、Spring Cloud Gateway、OpenFeign，能完成服务注册、路由转发和远程调用，了解 Sentinel 超时降级基本配置。"},
        {"缓存与 Redis", "熟悉 Redis 常用数据结构（String/Hash/List/Set/ZSet），了解 Cache Aside 缓存模式、分布式锁基本原理和使用场景。"},
        {"消息队列", "了解 RabbitMQ 的发送确认、消费确认、重试和幂等处理基本概念，在项目中实践过消息异步解耦场景。"},
        {"工程与协作素养", "熟悉 HTML、CSS、JavaScript，了解 Vue 2/3 基础用法，能配合前端完成前后端联调，独立开发简单管理后台页面。"},
        {"安全与认证", "了解 Spring Security + Token 实现登录态校验和角色级接口权限，实践过 JWT 认证和接口数据归属校验。"},
        {"测试与质量", "了解 JUnit 5 单元测试和 Mockito Mock 框架，能编写核心业务方法的单元测试，具备基本的代码质量意识。"},
        {"算法与数据结构", "熟悉常用数据结构与算法（数组、链表、树、排序、动态规划），LeetCode 刷题 200+，具备良好的代码逻辑思维。"},
        {"Java 语言与集合", "熟悉集合、异常、泛型和 JUC 常用组件，理解线程池、锁、CAS 基本使用场景；了解 JVM 内存区域、类加载和常见垃圾回收器。"},
        {"Java 语言与集合", "掌握 Java 基础语法与面向对象编程思想，熟悉集合框架（ArrayList、HashMap）、异常处理，了解 Lambda 表达式和 Stream 流式操作。"},
        {"并发编程", "熟悉 Java 多线程编程，理解线程安全问题，掌握线程池的基本使用，了解 ConcurrentHashMap 的线程安全机制。"},
        {"JVM", "了解 JVM 基本运行机制，包括内存区域划分、类加载过程和垃圾回收（GC）的基本流程。"},
        {"框架与工程化", "能使用 Spring Boot 快速搭建 Web 项目，了解 Spring 的 IOC 和 AOP 基本原理，具备基本的后端接口开发能力。"},
        {"存储与 MySQL", "熟练使用 MySQL 数据库，能编写常见 SQL 查询语句，了解索引、事务和锁机制的基本概念。"},
        {"缓存与 Redis", "熟悉 Redis 常用数据类型（String、Hash），了解其在项目中的缓存应用场景，能实现热点数据缓存。"},
        {"消息队列", "熟悉 RabbitMQ 消息队列的基本使用，能在项目中实现简单的异步任务处理或业务逻辑解耦。"},
        {"工程与协作素养", "熟悉 Git 版本控制工具，能进行代码提交、分支管理和协作开发；了解 CI/CD 的基本流程。"},
        {"算法与数据结构", "掌握数据结构基础（链表、栈、队列、二叉树），了解常用排序算法和基本的算法设计思路（DFS、BFS、动态规划）。"},
        {"工程与协作素养", "熟悉 HTML/CSS/JavaScript 基础，了解前后端交互的基本流程，能配合前端完成接口联调。"},
        {"工程与协作素养", "熟练使用 Maven 进行项目依赖管理和构建，了解多模块项目的组织结构。"},
        {"工程与协作素养", "具备良好的编码习惯，遵循《阿里巴巴 Java 开发手册》规范，会使用 IDEA 进行代码调试和性能分析。"},

        // ===== 中级（29 条）=====
        {"Java 语言与集合", "熟练掌握 Java 集合、反射、并发、IO、多线程等技术，深入理解 JUC 包下线程池、ConcurrentHashMap、AQS 等核心组件实现原理。"},
        {"框架与工程化", "熟练掌握 Spring、Spring MVC、Spring Boot、MyBatis Plus、Spring Cloud Alibaba 等主流技术框架，理解 Spring IOC/AOP 核心原理和 Bean 生命周期。"},
        {"存储与 MySQL", "熟练掌握 MySQL、PostgreSQL 数据库以及锁、索引和事务相关原理，具备慢 SQL 调优经验，能通过执行计划分析和优化联合索引，解决 N+1 查询问题。"},
        {"缓存与 Redis", "熟练使用 Redis 缓存数据库，掌握 Redis 的数据结构、持久化机制、分布式锁原理（Redisson）以及缓存穿透/击穿/雪崩解决方案，具备 Cluster + Sentinel 高可用部署经验。"},
        {"消息队列", "熟练使用 Kafka、RocketMQ 消息队列，掌握消息可靠投递（Publisher Confirm）、消费确认、重试机制、幂等处理和死信队列方案，具备高吞吐消息系统实践经验。"},
        {"分布式与微服务", "精通 Spring Cloud、Spring Cloud Alibaba、Dubbo 及各组件原理（Nacos 注册中心、Gateway 网关、Sentinel 限流降级、Seata 分布式事务），具备分布式服务开发与系统集成经验。"},
        {"JVM", "熟悉 JVM 内存模型、类加载机制、垃圾回收算法等基本原理，具备 JVM 参数调优与线上 OOM 问题排查经验，能使用 Arthas、jstack、jmap 等工具定位线上问题。"},
        {"分布式与微服务", "熟练使用 XXL-JOB 分布式任务调度框架、Elastic-Job，掌握任务分片、失败重试、日志追踪和动态配置，实践过定时任务解耦和大数据量批处理场景。"},
        {"搜索与检索", "熟悉 Elasticsearch 搜索引擎，掌握倒排索引、分词器、聚合查询和性能优化；了解 MongoDB 非关系型数据库，具备多类型数据存储选型经验。"},
        {"框架与工程化", "熟悉常用设计模式（单例、工厂、策略、代理、适配器、模板方法、责任链），具有实际项目应用经验，能在业务代码中合理运用模式提升可扩展性。"},
        {"工程与协作素养", "熟练使用 Docker 容器化部署，了解 Kubernetes 基本概念和 Pod/Service/Deployment 配置，掌握 Nginx 反向代理、负载均衡和静态资源托管。"},
        {"工程与协作素养", "熟悉 Jenkins、GitLab CI/CD 自动化构建流水线，掌握 Maven 多模块项目管理、SonarQube 代码质量检测，具备 DevOps 基础实践经验。"},
        {"分布式与微服务", "理解分布式事务 CAP/BASE 理论，实践过 TCC 补偿模式、可靠消息最终一致性方案，能解决跨服务数据一致性问题。"},
        {"框架与工程化", "精通 RESTful API 设计规范，掌握 OpenAPI/Swagger 接口文档管理，具备接口版本控制、参数校验、统一异常处理和接口性能优化经验。"},
        {"工程与协作素养", "熟悉 ELK Stack（Elasticsearch、Logstash、Kibana）日志收集分析，了解 Prometheus + Grafana 监控体系，具备线上问题快速定位能力。"},
        {"技术栈总览", "后端：Spring Boot 3.x / Spring Cloud Alibaba / MyBatis Plus / Seata / XXL-JOB；中间件：Kafka 3.x、Redis 7.x（Cluster + Sentinel）、Elasticsearch 8.x、MongoDB 7.x、MinIO；数据库：MySQL 8（索引优化、MVCC、锁、分库分表设计）；运维：Docker、Nginx、Git、Linux。"},
        {"JVM", "深入理解 JVM 内存模型与 GC 机制，能使用 jstack、jmap、MAT 等工具进行内存泄漏排查和 GC 日志分析。"},
        {"并发编程", "熟练掌握 Java 并发编程，深入理解线程池（ThreadPoolExecutor）参数配置、锁机制（synchronized/ReentrantLock）、AQS 原理及 JUC 并发工具包。"},
        {"分布式与微服务", "熟练使用 Spring Boot、Spring Cloud（Nacos、OpenFeign、Gateway），能独立完成微服务模块的设计、开发和部署。"},
        {"框架与工程化", "深入理解 Spring IOC/AOP 原理，熟悉 Spring Boot 自动配置机制，能根据业务需求进行自定义 Starter 开发。"},
        {"存储与 MySQL", "熟练掌握 MySQL 索引优化、执行计划分析（explain）、事务隔离级别和 MVCC 实现原理，有慢查询优化实战经验。"},
        {"缓存与 Redis", "熟练使用 Redis 实现缓存设计、分布式锁、延迟队列等场景，了解缓存穿透、击穿、雪崩的解决方案。"},
        {"消息队列", "熟练使用 Kafka/RocketMQ 处理消息可靠投递、幂等消费、延迟队列、死信队列等生产级场景。"},
        {"框架与工程化", "熟悉 MyBatis/MyBatis-Plus 的持久层开发，能编写高效 SQL，了解动态 SQL 和一二级缓存机制。"},
        {"系统设计", "具备高并发场景（秒杀、拼团）的系统设计经验，能通过 Redis 流量削峰、异步落库、分布式锁等方案保障系统稳定性。"},
        {"工程与协作素养", "熟悉 Docker 容器化部署，能编写 Dockerfile 并进行基本的容器编排，了解 Kubernetes 核心概念。"},
        {"测试与质量", "熟练使用 JUnit5 + Mockito 编写单元测试，了解 TDD 开发模式，能保障代码覆盖率。"},
        {"存储与 MySQL", "有使用 MyCat/ShardingSphere 进行分库分表的实践经验，了解分布式 ID 生成方案（Snowflake）。"},
        {"工程与协作素养", "熟悉 Linux 常用命令，能进行服务器日志排查和基本的性能监控（top、free、netstat 等）。"},

        // ===== 高级（25 条）=====
        {"JVM", "深入理解 JVM 内存模型、垃圾回收机制（CMS/G1/ZGC）和类加载器，具备线上 JVM 性能调优实战经验：曾将订单服务从 CMS 切换至 G1 并调整 Region 大小，使单机承载能力提升 50%，GC 暂停时间降低 30%。"},
        {"系统设计", "具备万级 QPS 高并发系统设计经验，精通缓存多级架构（本地缓存+Caffeine+Redis）、限流降级（Sentinel/Guava RateLimiter）、异步削峰（消息队列）和热点 Key 处理方案。"},
        {"分布式与微服务", "精通分布式系统核心理论（CAP、BASE、一致性协议 Raft/Paxos），具备分布式锁、分布式 ID（雪花算法）、分布式事务（TCC/Seata/可靠消息）和分布式调度的大规模落地经验。"},
        {"分布式与微服务", "主导过中大型系统从单体到微服务的架构拆分，精通领域驱动设计（DDD）、服务边界划分、API 网关、服务网格（Service Mesh）和全链路追踪（SkyWalking/Zipkin）。"},
        {"存储与 MySQL", "精通 MySQL 底层原理（InnoDB 存储引擎、MVCC、锁机制、事务隔离级别），具备分库分表（ShardingSphere/MyCat）、读写分离、主从复制和数据迁移的大规模实践经验。"},
        {"消息队列", "精通 Kafka/RocketMQ 底层原理（分区副本机制、ISR、高水位、消费位点管理），具备百万级 TPS 消息系统架构设计、消息顺序性保障和 Exactly-Once 语义实现经验。"},
        {"工程与协作素养", "精通 Kubernetes 容器编排（Pod/Service/Ingress/HPA/Operator），掌握 Helm 包管理、Istio 服务网格、Terraform 基础设施即代码，具备 AWS/阿里云云原生架构落地经验。"},
        {"系统设计", "具备全链路性能优化能力：从 SQL 优化、缓存策略、JVM 调优、线程池配置到 Nginx 调优、Tomcat 调优、网络优化，曾主导系统性能优化项目使核心接口响应时间降低 60%。"},
        {"系统设计", "精通高可用系统设计（熔断、降级、限流、隔离、重试、超时控制），具备线上故障应急响应和根因分析（RCA）能力，建立过系统健康度监控和自动巡检体系。"},
        {"系统设计", "具备丰富的技术选型和架构决策经验，能根据业务场景（并发量、数据量、一致性要求、成本）合理选择技术栈，主导过多个核心系统的技术升级和架构演进。"},
        {"工程与协作素养", "精通代码审查（Code Review）流程和标准，建立过团队编码规范和最佳实践，具备大规模代码重构经验，能通过设计模式和架构优化提升系统可维护性。"},
        {"综合画像", "拥有超过 8 年一线 Java 开发经验，技术根基扎实。不仅熟练使用 Spring Cloud、Spring Boot 进行微服务架构设计，更深入理解其核心原理。对 JVM 性能调优、多线程并发编程有实践心得，能从原理层思考和解决问题，而非仅停留在 API 使用层面。"},
        {"JVM", "精通 JVM 底层原理，深入理解内存模型、GC 算法（CMS/G1/ZGC）、类加载机制和 JIT 优化，具备生产环境 JVM 调优经验。"},
        {"并发编程", "精通 Java 并发编程，深入理解 AQS 源码、CAS 机制、无锁编程和 CompletableFuture 异步编排，能设计高吞吐的并发方案。"},
        {"分布式与微服务", "精通 Spring 生态体系，深入理解 Spring Cloud Alibaba（Nacos/Sentinel/Seata）的原理与最佳实践，具备微服务架构设计能力。"},
        {"分布式与微服务", "精通分布式系统设计，熟悉 CAP 理论、分布式事务（Seata Saga 模式）、最终一致性方案（消息表 + 本地事务）。"},
        {"系统设计", "具备全链路性能调优经验，能通过 Arthas、SkyWalking 进行线上问题定位，通过线程池参数优化、锁粒度控制、对象池复用等手段提升系统吞吐。"},
        {"系统设计", "精通高并发架构设计，熟悉限流（令牌桶/漏桶）、降级（Sentinel/Hystrix）、熔断机制和异步化方案。"},
        {"搜索与检索", "熟练使用 Elasticsearch 进行全文检索和聚合分析，具备分词器定制和查询性能优化经验。"},
        {"框架与工程化", "深入理解设计模式（工厂、代理、责任链、策略等），能在架构设计中合理运用，具备代码重构和技术债务治理经验。"},
        {"工程与协作素养", "熟练使用 Jenkins/GitLab CI 构建 CI/CD 流水线，了解自动化测试、灰度发布和蓝绿部署流程。"},
        {"系统设计", "具备多线程、高并发、高可用系统的实际落地经验，能在项目架构中做出适应性设计决策。"},
        {"分布式与微服务", "精通 Dubbo/gRPC 等 RPC 框架的服务治理，熟悉服务注册发现、负载均衡、泛化调用和协议设计。"},
        {"NoSQL 存储", "熟悉 MongoDB 副本集与分片集群的部署与运维，能根据业务场景选择合适的 NoSQL 方案。"},
        {"工程与协作素养", "具备代码质量管控经验，能制定团队编码规范，使用 SonarQube/Checkstyle 进行静态代码检查。"},

        // ===== 资深 / 架构师（22 条）=====
        {"综合画像", "10 年+ Java 后端开发经验，5 年+ 架构设计经验。主导过多个千万级用户系统的架构设计与演进，精通微服务架构、分布式系统、高并发高可用设计，具备从 0 到 1 搭建中大型技术体系的能力。"},
        {"综合画像", "深入钻研 Java 并发编程，熟悉 volatile、线程池等核心组件源码；掌握 JVM 内存模型与 GC 机制，对 MySQL 事务、索引、锁及 Redis 数据结构、持久化、分布式锁有系统性理解，具备从应用到底层的全链路技术视野。"},
        {"工程与协作素养", "8 年+ Java 开发经验，3 年+ 团队管理/技术负责人经验。具备中大型项目架构落地经验，能带领 10-20 人技术团队完成需求交付、技术规划和人才培养，主导过团队技术栈升级和工程化体系建设。"},
        {"系统设计", "精通领域驱动设计（DDD）和业务中台建设，具备复杂业务系统的领域建模、服务拆分和中台化改造经验，曾主导订单中台、用户中心等核心业务平台的架构设计与落地。"},
        {"大数据与实时计算", "熟悉 Flink 流式处理、状态管理、容错机制等基本原理，具备实时数据处理项目实践经验；了解 Hadoop/Spark 大数据生态，能设计流批一体的数据处理架构。"},
        {"AI 研发效能", "了解 RAG、MCP、Skill、AI Agent 智能体等主流 AI 技术，熟练应用 AI 辅助编程，具备将大模型能力集成到 Java 后端系统的实践经验（如智能客服、文档解析、代码生成等场景）。"},
        {"安全与认证", "精通 Web 安全（OWASP Top 10）、身份认证（OAuth2.0/OIDC/SAML）、数据加密和权限设计，具备金融级系统安全架构经验，主导过系统安全漏洞修复和等保合规建设。"},
        {"系统设计", "具备国际化系统架构经验，精通多语言（i18n）、多时区、多币种、多租户（SaaS）设计，了解海外云平台（AWS/Azure/GCP）部署和合规要求（GDPR）。"},
        {"工程与协作素养", "持续输出技术博客和开源项目，具备技术分享和团队培训能力，主导过内部技术委员会和代码规范建设，推动团队技术成长和工程效能提升。"},
        {"综合画像", "资深技术专家，兼具深度与广度：拥有超过 10 年一线 Java 开发经验，技术根基扎实。不仅熟练使用 Spring Cloud、Spring Boot 等主流框架进行微服务架构设计与开发，更深入理解其核心原理。对 JVM 性能调优、多线程并发编程有实践心得，能从原理层思考和解决问题。具备分布式架构与高可用设计经验，主导过多个核心系统的架构演进和性能优化。"},
        {"Java 语言与集合", "精通现代 Java（17/21+）特性，包括 Records、Sealed Classes、虚拟线程（Project Loom）、Streams 与响应式编程范式。"},
        {"系统设计", "精通微服务架构设计，熟悉 DDD 领域驱动设计的服务拆分方法论，能主导从单体到微服务的架构演进。"},
        {"工程与协作素养", "精通云原生技术栈（Docker/Kubernetes/Service Mesh），具备从 0 到 1 构建云原生应用平台的架构设计经验。"},
        {"系统设计", "精通分布式系统的高可用设计，熟悉多活架构、异地容灾、服务网格（Istio）等方案，能保障核心系统 99.99% 可用性。"},
        {"系统设计", "精通性能调优全链路，能通过 JVM 调优、数据库优化、缓存策略、异步化等多种手段系统性提升系统性能，有将 TP99 从秒级降至毫秒级的实战成果。"},
        {"消息队列", "精通 Kafka/Event Streaming 架构，熟悉事件驱动架构（EDA）的设计与落地，能处理百万级消息吞吐的场景。"},
        {"系统设计", "具备技术选型与架构决策能力，能根据业务规模、团队能力和成本约束做出合理的技术方案取舍。"},
        {"安全与认证", "精通安全架构设计，熟悉 OAuth2/JWT/SSO 认证方案，了解 PCI-DSS、GDPR 等合规要求，具备安全编码和代码审计经验。"},
        {"工程与协作素养", "具备技术团队管理经验，能主导代码评审、技术分享、新人培养，推动团队技术能力持续提升。"},
        {"大数据与实时计算", "精通大数据技术栈（Spark/Flink/Hadoop），有 PB 级数据处理和实时计算平台的架构设计经验。"},
        {"AI 研发效能", "熟悉 AI 工程化方向，了解 LangChain、RAG、向量数据库等技术，能将大模型能力集成到 Java 后端系统中。"},
        {"工程与协作素养", "具备良好的英文技术文档阅读能力，能跟踪国际前沿技术动态，有开源项目贡献或技术博客输出。"}
    };

    /**
     * 整段样板 16 段：完整技能部分（2026-10 网络收集，脱敏整理，仅示范整体组织——
     * 条数、能力域划分、深浅搭配、排列顺序；内容一律不得搬用）。
     * 结构：{职级档位, 样板标题, 样板正文（多行，每行一条）}。
     */
    private static final String[][] SECTION_SEED = {

        // ===== junior（0~3 年）=====

        {"junior", "整段样板 · 校招/实习 · 分组小标题式", """
            - 【Java】熟悉集合、异常、泛型和 JUC 常用组件，理解线程池、锁、CAS 和 CompletableFuture 的基本使用场景；了解 JVM 内存区域、类加载和常见垃圾回收器。
            - 【Web】熟悉 Spring Boot、Spring MVC、MyBatis-Plus，理解 Bean 生命周期、AOP、参数校验和统一异常处理。
            - 【微服务】熟悉 Nacos、Spring Cloud Gateway、OpenFeign、Sentinel、Seata，能完成服务注册、路由转发、远程调用、超时降级和基础事务边界分析。
            - 【数据库与缓存】熟悉 MySQL 索引、事务、锁和慢 SQL 分析；熟悉 Redis 常用数据结构、Cache Aside、分布式锁、Lua 和 Stream 消费组。
            - 【消息与工程化】熟悉 RabbitMQ 的发送确认、消费确认、重试和幂等处理；会使用 Git、Maven、Docker、Linux 常用命令和 Nginx 完成项目运行与部署。
            """},
        {"junior", "整段样板 · 应届 · 无小标题纵深式", """
            - 熟悉 Java 核心 API，掌握常用集合底层的实现数据结构，了解多线程的基础使用与常见的线程安全问题。
            - 熟悉 Spring、SpringBoot、MyBatis，了解 Spring 的 IOC 与 AOP 设计思想以及常见应用场景。
            - 熟悉 MySQL 核心概念，掌握常见 SQL 编写与执行计划查看，了解事务与索引的基本原理。
            - 了解 Redis 基本数据类型，熟悉其在缓存场景中的日常使用，了解缓存穿透与缓存雪崩等基本概念。
            - 了解 TCP 和 IP 基础网络协议，熟悉 Linux 常见目录结构与日常排查服务状态的命令。
            """},
        {"junior", "整段样板 · 1~2 年社招 · 逐项展开式", """
            - 熟练掌握 Java 基础语法以及常用 API 和 OOP 编程思想，具有良好的编码习惯。
            - 熟练掌握 MyBatis、Spring、SpringMVC 整合开发，掌握 Servlet，了解 Hibernate；经常在工作中使用 Spring Boot 快速构建项目，理解相关框架组件以及原理。
            - 熟练掌握 MySQL 复杂 SQL 编写与常用函数、数据库优化方案以及 SQL 优化，在工作中有相关 SQL 优化经验；了解 Oracle、Redis、JDBC、Spring Data JPA。
            - 熟练使用 Redis 分布式缓存和生产环境常见问题解决方案（如缓存雪崩、穿透、缓存并发竞争、缓存与数据库双写不一致等处理）。
            - 熟练掌握 Spring Cloud 微服务治理架构，参与过 Spring Cloud 微服务相关项目开发，对多线程以及高并发、高可用有过一些相关研究。
            - 熟练掌握 JVM 虚拟机内存调优和 Tomcat 并发调优等参数设置与操作经验；使用 Tomcat 发布部署 Web 项目，使用反向代理服务器 Nginx。
            - 熟悉 RabbitMQ 消息中间件原理及使用，以及生产环境常见问题解决方案。
            - 熟练操作 Linux，在工作中经常使用；熟练使用 IDEA、Git 等工具。
            """},

        // ===== mid（3~5 年）=====

        {"mid", "整段样板 · 5 年 · 支付方向 · 简洁纵深式", """
            - 有扎实 Java 编程基础，熟悉各类设计模式、JUC 并发编程、JVM 内存管理及调优。
            - 熟悉 Spring Boot、MyBatis、Dubbo 等相关中间件并阅读过相关源码，并根据需求进行扩展开发。
            - 熟悉 MySQL，具有 SQL 优化、分库分表、索引优化等方面经验。
            - 熟悉 CI/CD 自动化部署，了解 Docker、Portainer、Kubernetes 等。
            - 熟练使用 StarUML、Visio、PowerDesigner 等各项 UML 建模工具。
            - 使用过其他技术栈语言，如 Golang、Python 等。
            """},
        {"mid", "整段样板 · 社招 · 按主题四段式", """
            - 【Java 技术栈】拥有扎实的 Java 编程基础和深入理解 Java 核心概念。熟练掌握 Spring Framework，包括 Spring Boot 和 Spring Cloud，能够构建微服务架构和 RESTful API。熟练使用 Hibernate 和 JPA，实现高效的数据模型映射和事务管理。
            - 【数据库技术】精通 MySQL、Oracle 等关系型数据库，具备数据库设计、性能调优和复杂 SQL 查询编写的能力。熟悉 MongoDB、Redis 等 NoSQL 数据库，能够根据业务需求选择合适的数据存储解决方案。实施数据库备份和恢复策略，确保数据的高可用性和一致性。
            - 【版本控制和持续集成】熟练使用 Git 进行源代码管理，熟悉分支管理、合并、冲突解决等操作。有使用 Jenkins 进行持续集成和自动化部署的经验，包括构建流水线的设置和维护。了解 Docker 等容器化技术，能够将应用容器化，提高部署的灵活性和环境一致性。
            - 【代码质量保证】注重编写可读且易维护的代码，遵循 SOLID 原则和设计模式。实践单元测试和集成测试，使用 JUnit 和 Mockito 等工具，确保软件质量。采用 SonarQube 等静态代码分析工具，提升代码健康度和性能。
            """},
        {"mid", "整段样板 · 5 年 · 大厂社招 · 深度四条式", """
            - 具备扎实的 Java 并发编程基础，深入理解 JVM 内存模型、类加载机制与垃圾回收算法，拥有丰富的线上 OOM 与 GC 调优实战排障经验。
            - 精通 MySQL 的 InnoDB 存储引擎底层结构与 MVCC 机制，具备千万级表数据的分库分表与复杂慢 SQL 深度调优经验；熟练掌握 Redis 的高可用架构与持久化机制，能运用分布式锁处理缓存雪崩与穿透等高可用问题。
            - 熟练掌握 Spring Boot 与 Spring Cloud Alibaba 微服务生态，熟悉 Nacos 注册中心、Sentinel 限流降级及 Gateway 全局网关的底层运行逻辑与定制化二次开发。
            - 熟练掌握 Kafka 或 RocketMQ 消息队列的内部架构，具备处理消息防丢失、防重复消费及海量消息堆积的成熟方法论；熟悉 Elasticsearch 搜索引擎的倒排索引机制与复杂聚合查询优化。
            """},

        // ===== senior（5~8 年）=====

        {"senior", "整段样板 · 高级社招 · 能力+落点式", """
            - 熟练掌握 Java 语言及常用集合框架，了解 ArrayList、HashMap 等的底层原理；熟悉 Java 并发编程，具有线程池、AQS 等实践经验。
            - 了解 JVM 运行时内存区域，类加载机制以及 GC 算法，项目中有排查解决 OOM 问题的经验。
            - 熟练掌握 Spring Boot 框架的使用，熟练掌握 Spring Data JPA、MyBatis 等 ORM 框架的使用；熟悉 Spring Cloud 框架常用组件的使用，具有微服务项目实战经验。
            - 熟悉 MySQL，对数据库事务、锁及索引有深入了解，具有 SQL 调优实践经验；了解 Oracle 数据库。
            - 熟悉 Redis 的使用，对常用数据结构、持久化机制，缓存雪崩、击穿、穿透等有一定了解，具有实战经验。
            - 熟悉 Linux 操作系统及常用指令，能够编写 Shell 脚本，有部署项目的实战经验，了解 Nginx、Docker 的使用。
            """},
        {"senior", "整段样板 · 全栈+技术管理 · 板块式", """
            - 【全栈开发与架构设计】后端：精通分布式系统架构（微服务/Serverless）、高并发设计，熟悉 Java/Python/Go/Node.js 等，熟练使用 Spring Boot/Django/FastAPI/Gin 等框架。前端：掌握 React/Vue/Next.js 等主流框架，具备复杂 SPA、低代码平台开发经验，熟悉 Web 性能优化与前端工程化。数据库：熟练使用 MySQL/PostgreSQL/MongoDB/Redis，擅长 SQL 优化、分库分表及缓存策略设计。DevOps：精通 CI/CD（Jenkins/GitLab CI）、容器化（Docker/K8s）、云服务（AWS/Azure/阿里云），主导过从零搭建云原生架构
            - 【技术管理与团队领导】负责技术战略规划，主导技术选型与架构演进，平衡短期需求与长期技术债。擅长跨职能团队协作（产品/测试/运维），建立高效研发流程（敏捷/Scrum），提升团队交付效率 30%+。制定代码规范、Review 机制与自动化测试体系，降低线上事故率
            - 【产品与运维实战】深度参与需求分析、原型设计，主导过多个从 0 到 1 的互联网产品（如 SaaS 平台、数据中台）。设计高可用（HA）架构，实现监控告警（Prometheus/Grafana）、日志分析（ELK）、灾备方案，保障 SLA 99.9%+
            - 【AI 与大模型应用】具备 AI 工程化经验，熟悉 LangChain/LLaMA 等框架，主导过基于大模型的智能客服、文档生成等场景落地。精通 Prompt Engineering、模型微调（LoRA/P-Tuning），优化推理性能与成本
            - 【质量保障与测试】搭建自动化测试体系（单元/接口/UI 测试），集成 SonarQube 进行代码质量管控。熟悉性能测试（JMeter/LoadRunner），压测优化系统 TPS 2000+
            """},
        {"senior", "整段样板 · 国产化+AI 集成 · 专项式", """
            - 信创国产化适配：主导企业级平台国产化改造，完成银河麒麟、达梦/瀚高/万里、东方通 tongWeb、杉岩存储的适配联调与厂商认证对接，取得互认证书；熟悉 SQL 方言差异、事务隔离等坑点。
            - AI 智能体工程化：多 LLM 平台统一契约抽象与热切换、SSE 流式解析、智能体异常与超时降级兜底、企业 IM 机器人接入。
            - 技术栈：Java/Spring Boot/MyBatis-Plus/Redis/RocketMQ/MySQL·达梦/Docker/Linux/Vue。
            - 擅长国产化替换与性能治理、AI 对话接入既有业务、分布式消息调度；持有 6 项已公开发明专利。
            """},
        {"senior", "整段样板 · 电商/支付 · 微服务栈式", """
            - 熟练使用 Java、Spring Boot、Spring MVC、MyBatis 及 MyBatis-Plus，熟悉 Spring Cloud Alibaba 微服务技术栈，包括 Nacos 注册与配置管理、Sentinel 限流熔断、Seata 分布式事务、RocketMQ 消息队列，并结合 Spring Cloud Gateway、OpenFeign 实现网关路由及服务调用。
            - 熟悉 MySQL、Oracle、Redis，具备 RabbitMQ、Elasticsearch、XXL-JOB 项目实践。
            - 可完成数据库设计、后端接口开发、第三方系统对接、支付对账、权限管理及若依系统二次开发，使用 Tomcat、Nginx 部署维护项目。
            """},
        {"senior", "整段样板 · 传统企业+AI 集成 · 混合式", """
            - 熟悉 AI 相关技术，了解大语言模型（LLM）的基本原理与应用场景，具备 Prompt 工程实践经验。
            - 了解 Spring AI、LangChain4j 等 AI 应用开发框架，能够将 AI 能力集成到 Java 业务系统中。
            - 熟悉 RAG（检索增强生成）、向量数据库（如 Milvus、Chroma）等技术的应用，能够搭建基于知识库的智能问答系统。
            - 了解 OpenAI、DeepSeek、通义千问等主流大模型 API 的调用与集成；具备 AI 辅助开发经验，熟练使用 GitHub Copilot、Cursor 等编程工具提升开发效率。
            - 熟练掌握 Spring、SpringMVC、Mybatis 等框架原理，了解 Hibernate 框架。
            - 熟练使用 MySQL 数据库技术及数据库的优化，了解 Oracle、SQL Server 数据库。
            - 熟练使用 RESTful 接口，熟练进行分布式框架项目的开发，熟练使用 Redis 等缓存技术进行业务开发。
            - 熟练掌握前端 JavaScript、JQuery 等技术，了解 HTML、CSS、Bootstrap 框架。
            """},

        // ===== expert（8 年+）=====

        {"expert", "整段样板 · 资深架构师 · 主题块式", """
            - 【16 年 Java 架构实战与 AI 融合】主导过日活 600W+ 系统的高可用架构设计，系统可用性达 99.99%。独立完成基于 LangGraph 的多角色智能体系统（RAG+多 Agent 协作+记忆管理+语音交互），具备将 AI 应用架设在 Java 技术栈并保障生产级稳定性的能力。
            - 【高可用与性能治理】擅长诊断并修复生产环境长期问题（周期性崩溃、慢 SQL、锁竞争、内存溢出），通过限流熔断（Sentinel）、JVM 调优（GC 停顿<50ms）、异步解耦、缓存升级等手段，将系统平均响应时间压缩 70%，QPS 提升 4 倍。
            - 【微服务治理与分布式事务实战】精通微服务边界划分（基于 DDD 限界上下文），主导将单体系统拆分为 12 个核心领域服务；精通 Spring Cloud Gateway+OAuth2/JWT 统一认证与细粒度权限控制，实战落地 RabbitMQ 分布式事务最终一致性方案；设计数据流向规范——写链路走 MySQL（事务强一致），读链路走 ES/Redis（最终一致）；通过周度评审机制保障架构平滑演进，定期梳理技术债务并排序偿还。
            - 【行业数据基础设施构建】深耕贸易/供应链领域 5 年，善于用实时数据管道（Binlog+Flink CDC）替换低效轮询，将同步延迟从分钟级降至秒级，支撑日均数百万全量+数十万增量数据实时入 ES，宽表查询从 10s+ 优化至亚秒级。
            - 【AI 落地】将 LLM 嵌入真实业务流——自主构建基于 LangGraph 的多角色智能体系统（RAG 全链路自研：语义分块+向量库+BM25+RRF 混合检索+重排，答案相关度显著提升）；善于将 AI 能力与 Java 微服务生态融合，保障生产级稳定。
            """},
        {"expert", "整段样板 · 架构师 · 概括四条式", """
            - 多年 Java 后端与系统架构经验。技术栈以 Spring Boot、Spring Cloud 为核心，辅以 MySQL、Redis、Kafka、Elasticsearch 等组件，熟练使用 Docker、K8s 与 Jenkins 完成容器化部署和持续交付。
            - 擅长高并发与资金安全类系统设计，包括分布式锁、幂等与对账、分布式事务、限流熔断降级。
            - 具备 JVM 调优、慢 SQL 治理和线上疑难问题定位能力，主导过单体系统微服务化改造与数据库分库分表。
            - 注重工程规范建设，通过统一脚手架、代码评审与技术文档沉淀提升团队协作效率，习惯以压测数据和监控指标驱动架构决策。
            """},
        {"expert", "整段样板 · 技术专家+管理 · 复合画像式", """
            - 【技术专长】资深 Java 全栈专家，精通微服务架构设计、分布式系统开发及高性能代码编写。熟悉主流技术栈，包括 Spring Boot、Spring Cloud、Mybatis、Spring AI 等，熟悉 MySQL、PG、达梦、Oracle 等数据库，并熟练掌握 Redis、Kafka、ES、Hadoop/Hive/Spark/HBase 等中间件与大数据平台技术。擅长高并发、高可用系统架构设计，擅长诸如 starter 等基础组件的设计、研发及管理，并熟悉 DevOps、Docker、K8S 等云原生技术。
            - 【管理经验】拥有超过 10 年的技术管理经验，曾担任架构师、技术总监、首席架构师等职。精通技术战略规划、团队组建与管理，具备大型项目群管理能力，并熟悉从招投标到交付的全生命周期管理。
            - 【行业深耕】长期专注于金融科技领域，对银行、证券的核心业务系统，以及信贷、征信、反洗钱、供应链金融、基金运营管理等业务有深入的理解和实践经验。
            """},
        {"expert", "整段样板 · 金融支付方向 · 深耕画像式", """
            - 9 年+ Java 后端研发经验，长期深耕金融支付、助贷及电商系统。
            - 熟练掌握 Java、Spring Boot、Dubbo、MyBatis、MySQL、Redis、RocketMQ、ShardingSphere，擅长复杂业务建模、高并发接口、分库分表、异步解耦与系统稳定性优化。
            - 具备 Python、FastAPI、LangGraph 及 MCP 工具集成经验，可将 AI 接入现有业务流程。
            - 可承接后端模块开发、第三方接口对接、系统功能改造、故障排查及数据处理核对自动化，重视规则确认、异常处理与交付验收。
            """},
        {"expert", "整段样板 · 高级开发 · 逐项纵深式", """
            - 精通 Java 语言及其生态系统，具备深厚的 Java 企业应用开发能力，能够高效设计并实现复杂的企业级应用。了解 Go 语言、了解 Python。
            - 熟练掌握多线程编程技术，包括 JUC 包的使用，能够有效管理线程资源，提升系统并发性能。
            - 深入了解 JVM 工作原理，包括内存管理、垃圾回收机制等，能够针对应用性能进行调优。
            - 精通 Spring Boot 框架，能够快速搭建并开发 Spring 应用，实现业务逻辑的快速迭代；熟悉 Spring Cloud 和 Spring Cloud Alibaba、Nacos 微服务架构。
            - 熟练掌握 MyBatis、MyBatis Plus 和 Spring Data JPA 等持久层框架，能够高效处理数据库交互逻辑。
            - 熟悉 Oracle、MySQL 等关系型数据库的工作原理，能够针对业务需求进行数据库设计优化，使用过国产达梦数据库。
            - 熟悉 Redis、MongoDB 和 Elasticsearch 等非关系型数据库，能够结合业务需求选择合适的数据存储方案。
            - 熟悉 ActiveMQ 和 RocketMQ 等消息队列的基本原理和使用。
            - 熟练使用多种 AI 编程工具，通过需求拆解与迭代调试，快速完成数据处理及接口的代码实现。
            """}
    };
}
