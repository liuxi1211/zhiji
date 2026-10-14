package com.zhiji.source.config;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 素材库存量库补列（SQLite 无独立迁移工具，schema.sql 的 CREATE TABLE IF NOT EXISTS 改不了既有表）：
 * 启动时按 PRAGMA table_info 检查 src_item.content_type，缺失则 ALTER TABLE 补上——幂等，新库命中 schema.sql 后此处空转。
 * 最高优先级：补列必须先于业务 runner 执行（与 SchemaPatcher 同约定）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SourceSchemaPatch implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public SourceSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(src_item)");
        boolean hasContentType = columns.stream().anyMatch(col -> "content_type".equals(col.get("name")));
        if (!hasContentType) {
            jdbc.execute("ALTER TABLE src_item ADD COLUMN content_type TEXT "
                    + "CHECK (content_type IS NULL OR content_type IN ('kp','doc'))");
            log.info("src_item 已补列 content_type（存量库迁移）");
        }
    }
}
