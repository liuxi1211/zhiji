package com.zhiji.source.config;

import com.zhiji.common.config.ConfigRegistry;
import com.zhiji.common.config.ConfigSection;
import com.zhiji.common.config.FieldDef;
import com.zhiji.common.config.FieldType;
import com.zhiji.common.constant.ConstantRegistry;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.repo.KbTopicMapper;
import com.zhiji.kb.repo.KbDocMapper;
import com.zhiji.source.domain.DomainConfig;
import com.zhiji.source.repo.SourceItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置域「知识领域」（kb.domains）：领域 code + 显示名的自由行表格，系统设置页可增删改，保存即生效。
 * 取代 Domain 枚举（5.3 种子清单）成为运行时事实源：保存后经 ConstantRegistry.override 刷新
 * 常量组 domain，素材校验/主题校验/学习路径分域/AI 分类提示全部无感跟随。
 *
 * 删除保护：仍被素材或标准库内容引用的领域不可删除（validate 挡在落库前，防历史数据悬空无归属）。
 */
@Component
public class DomainSection implements ConfigSection<DomainConfig>, ApplicationRunner, Ordered {

    public static final String KEY = "kb.domains";

    private final SourceItemMapper sourceItemMapper;
    private final KbTopicMapper topicMapper;
    private final KbKpMapper kpMapper;
    private final KbDocMapper docMapper;
    private final ConstantRegistry constantRegistry;
    /** ObjectProvider 打破构造环：ConfigRegistry → 本域 → ConfigRegistry；保存钩子运行期才解析。 */
    private final ObjectProvider<ConfigRegistry> registry;

    private final JsonMapper jsonMapper = new JsonMapper();

    public DomainSection(SourceItemMapper sourceItemMapper, KbTopicMapper topicMapper,
            KbKpMapper kpMapper, KbDocMapper docMapper, ConstantRegistry constantRegistry,
            ObjectProvider<ConfigRegistry> registry) {
        this.sourceItemMapper = sourceItemMapper;
        this.topicMapper = topicMapper;
        this.kpMapper = kpMapper;
        this.docMapper = docMapper;
        this.constantRegistry = constantRegistry;
        this.registry = registry;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "知识领域";
    }

    @Override
    public String description() {
        return "素材与知识库的领域清单，增删改保存即生效；领域宜少而清晰，被内容引用的领域不可删除";
    }

    /** 配置卡片排序；ApplicationRunner 顺序与卡片 order 共用，配置加载（order=0）后发布常量组。 */
    @Override
    public int order() {
        return 10;
    }

    /** Ordered 接口方法：Spring 启动 runner 顺序复用卡片 order。 */
    @Override
    public int getOrder() {
        return order();
    }

    @Override
    public List<FieldDef> fields() {
        return List.of(
                FieldDef.of("code", "领域代码", FieldType.TEXT)
                        .width(180).help("小写英文标识，落库到素材与知识库的 domain_code").build(),
                FieldDef.of("name", "显示名称", FieldType.TEXT)
                        .minWidth(160)
                        .help("前端展示与 AI 分类提示用中文名").build(),
                FieldDef.of("coverage", "覆盖范围", FieldType.TEXTAREA)
                        .minWidth(360)
                        .help("覆盖主题清单 + 易混淆裁定（如「Redis 分布式锁归数据库不归分布式」）。"
                                + "向导选领域时展示给人看，同时注入 AI 领域建议提示词——写越清楚 AI 建议越准")
                        .build());
    }

    /**
     * 首启种子：原 5.3 固化清单 + 覆盖范围草案入库（老部署首次升级时同样以此补种；
     * coverage 与显示名一样进配置，存量库在设置页可改）。
     */
    @Override
    public DomainConfig defaults() {
        return new DomainConfig(List.of(
                new DomainConfig.Item("java", "Java 语言与生态",
                        "覆盖：Java 语言本身（集合/泛型/反射/IO）、并发编程（线程池/JUC/虚拟线程）、"
                                + "JVM（内存模型/GC/类加载/调优）、Spring 与 Spring Boot（IoC/AOP/事务/MVC）、"
                                + "MyBatis、Netty、Java 新版本特性。"
                                + "裁定：Spring 事务失效归本域不归分布式；Netty 实现 RPC 归本域不归分布式与架构。"),
                new DomainConfig.Item("database", "数据库",
                        "覆盖：MySQL（索引/事务/MVCC/锁/调优/日志）、Redis（缓存策略/分布式锁/持久化/集群）、"
                                + "MongoDB、分库分表落地、读写分离。"
                                + "裁定：Redis 分布式锁归本域不归分布式与架构；"
                                + "分库分表的整体架构权衡归分布式与架构，具体到某个库怎么分归本域。"),
                new DomainConfig.Item("middleware", "中间件",
                        "覆盖：消息队列（Kafka/RocketMQ/RabbitMQ 的模型/顺序/堆积/可靠性）、"
                                + "Elasticsearch（倒排索引/分词/写入与查询链路）、"
                                + "注册与配置中心（ZooKeeper/Nacos）、网关、定时任务调度。"
                                + "裁定：消息不丢的整体架构论证归分布式与架构，MQ 组件本身的使用与原理归本域。"),
                new DomainConfig.Item("distributed", "分布式与架构",
                        "覆盖：分布式事务（2PC/TCC/本地消息表/最终一致）、分布式锁与分布式 ID、"
                                + "CAP 与一致性协议、微服务治理（熔断/限流/降级/链路追踪）、"
                                + "高并发系统设计（秒杀/抢购/短链/签到）、DDD。"
                                + "裁定：具体中间件组件的使用与原理归中间件，多节点协作与整体方案设计归本域。"),
                new DomainConfig.Item("cs_basic", "计算机基础",
                        "覆盖：计算机网络（TCP/UDP/HTTP/HTTPS/握手挥手）、"
                                + "操作系统（进程线程/内存管理/IO 多路复用/零拷贝）、数据结构与算法。"
                                + "裁定：与具体语言或框架无关的底层原理归本域。"),
                new DomainConfig.Item("go", "Go 语言",
                        "覆盖：Go 语言本身（goroutine/channel/内存模型）、GMP 调度模型、GC、Go 生态框架。"),
                new DomainConfig.Item("engineering", "工程实践",
                        "覆盖：线上问题排查（CPU/内存/磁盘/网络/Arthas/日志分析）、监控告警、"
                                + "CI/CD、Docker 与 K8s 使用、设计模式、代码质量与重构。"
                                + "裁定：语言或框架自身的原理问题不归本域，怎么把系统运营好、排好障归本域。")));
    }

    @Override
    public DomainConfig parse(JsonNode json) {
        return jsonMapper.treeToValue(json, DomainConfig.class);
    }

    @Override
    public void validate(DomainConfig value) {
        if (value.items().isEmpty()) {
            throw BizException.validation("至少保留一个领域");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (DomainConfig.Item item : value.items()) {
            String code = item.code() == null ? "" : item.code().strip();
            String name = item.name() == null ? "" : item.name().strip();
            if (code.isEmpty() || !code.matches("[a-z][a-z0-9_]*")) {
                throw BizException.of(400, "SYS_PARAM_INVALID",
                        "领域代码不合法（小写字母开头，仅小写字母/数字/下划线）：" + code);
            }
            if (code.length() > 32) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "领域代码过长：" + code);
            }
            if (!codes.add(code)) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "领域代码重复：" + code);
            }
            if (name.isEmpty()) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "领域 " + code + " 的显示名称不能为空");
            }
        }
        requireNoReferencedDeletion(value);
    }

    /**
     * 删除保护：本次保存清单里缺失、且仍被素材/主题/知识点/文章引用的领域不可删除。
     * 引用中允许改名（code 才是落库主键），删除挡在落库前防历史数据悬空。
     */
    private void requireNoReferencedDeletion(DomainConfig incoming) {
        Set<String> codes = new LinkedHashSet<>();
        for (DomainConfig.Item item : incoming.items()) {
            codes.add(item.code().strip());
        }
        Set<String> referenced = new LinkedHashSet<>();
        collectDomainCodes(referenced, sourceItemMapper.selectObjs(
                new LambdaQueryWrapper<com.zhiji.source.domain.SourceItem>()
                        .select(com.zhiji.source.domain.SourceItem::getDomainCode)
                        .isNotNull(com.zhiji.source.domain.SourceItem::getDomainCode)));
        collectDomainCodes(referenced, topicMapper.selectObjs(
                new LambdaQueryWrapper<com.zhiji.kb.domain.KbTopic>()
                        .select(com.zhiji.kb.domain.KbTopic::getDomainCode)));
        collectDomainCodes(referenced, kpMapper.selectObjs(
                new LambdaQueryWrapper<com.zhiji.kb.domain.KbKp>()
                        .select(com.zhiji.kb.domain.KbKp::getDomainCode)));
        collectDomainCodes(referenced, docMapper.selectObjs(
                new LambdaQueryWrapper<com.zhiji.kb.domain.KbDoc>()
                        .select(com.zhiji.kb.domain.KbDoc::getDomainCode)));
        for (String code : referenced) {
            if (!codes.contains(code)) {
                throw BizException.of(400, "SYS_PARAM_INVALID",
                        "领域 " + code + " 仍被素材或知识库内容引用，不可删除（可改名）");
            }
        }
    }

    private static void collectDomainCodes(Set<String> sink, List<Object> rows) {
        for (Object row : rows) {
            if (row != null) {
                sink.add(String.valueOf(row));
            }
        }
    }

    /** 保存即发布：刷新常量组 domain 快照，前端下拉/校验下一步请求即用新清单。 */
    @Override
    public Object afterSave(Object value) {
        constantRegistry.override("domain", toItems(value));
        return value;
    }

    /**
     * 启动发布：ConstantRegistry 扫描枚举在 ContextRefreshedEvent，Domain 枚举退役后
     * 常量组 domain 由配置域自举——在 ConfigRegistry（order=0）加载完快照后发布。
     */
    @Override
    public void run(ApplicationArguments args) {
        DomainConfig config = registry.getIfAvailable() == null ? null
                : registry.getIfAvailable().get(KEY);
        if (config != null && !config.items().isEmpty()) {
            constantRegistry.override("domain", toItems(config));
        }
    }

    private List<ConstantRegistry.Item> toItems(Object value) {
        DomainConfig config = (DomainConfig) value;
        return config.items().stream()
                .map(item -> new ConstantRegistry.Item(item.code().strip(), item.name().strip(),
                        item.coverage() == null || item.coverage().isBlank() ? null : item.coverage().strip()))
                .toList();
    }
}
