package com.zhiji.kb.config;

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
 * 标准库存量库补列（SQLite 无独立迁移工具，schema.sql 的 CREATE TABLE IF NOT EXISTS 改不了既有表）：
 * 启动时按 PRAGMA table_info 检查缺失列并 ALTER TABLE 补上——幂等，新库命中 schema.sql 后此处空转。
 * 最高优先级：补列必须先于 KbBootstrap 等业务 runner 执行，否则查询新列直接报 no such column。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KbSchemaPatch implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public KbSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("kb_topic", "description",
                "TEXT", "主题覆盖范围描述：人读提示与 AI 定位共用（存量库迁移）");
    }

    private void addColumnIfMissing(String table, String name, String ddl, String reason) {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        boolean exists = columns.stream().anyMatch(col -> name.equals(col.get("name")));
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + ddl);
            log.info("{} 已补列 {}（{}）", table, name, reason);
        }
    }
}
