package com.zhiji.profile.config;

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
 * 技能条表补列（SQLite 无独立迁移工具，schema.sql 的 CREATE TABLE IF NOT EXISTS 改不了既有表）：
 * 对话流改造（PRD v2）为 prf_claim 增加 title 与 anchor_status，存量库按 PRAGMA 检查后 ALTER 补列，
 * 幂等——新库命中 schema.sql 后此处空转。
 * 最高优先级：补列必须先于业务 runner 执行（与 SchemaPatcher 同约定）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProfileSchemaPatch implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProfileSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public ProfileSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(prf_claim)");
        if (columns.isEmpty()) {
            return;
        }
        boolean hasTitle = columns.stream().anyMatch(col -> "title".equals(col.get("name")));
        if (!hasTitle) {
            jdbc.execute("ALTER TABLE prf_claim ADD COLUMN title TEXT NOT NULL DEFAULT ''");
            log.info("prf_claim 已补列 title（存量库迁移）");
        }
        boolean hasAnchorStatus = columns.stream().anyMatch(col -> "anchor_status".equals(col.get("name")));
        if (!hasAnchorStatus) {
            jdbc.execute("ALTER TABLE prf_claim ADD COLUMN anchor_status TEXT NOT NULL DEFAULT 'mapped'");
            log.info("prf_claim 已补列 anchor_status（存量库迁移：既有锚点视为已锚定）");
        }
        List<Map<String, Object>> profileColumns = jdbc.queryForList("PRAGMA table_info(prf_profile)");
        boolean hasGoodAt = profileColumns.stream().anyMatch(col -> "good_at".equals(col.get("name")));
        if (!hasGoodAt) {
            jdbc.execute("ALTER TABLE prf_profile ADD COLUMN good_at TEXT NOT NULL DEFAULT ''");
            log.info("prf_profile 已补列 good_at（存量库迁移）");
        }
        // 参考池两层结构改造：单条池 + 整段样板（kind 区分，section 按职级档位 grade 打标）
        List<Map<String, Object>> refColumns = jdbc.queryForList("PRAGMA table_info(prf_reference_claim)");
        if (!refColumns.isEmpty()) {
            boolean hasKind = refColumns.stream().anyMatch(col -> "kind".equals(col.get("name")));
            if (!hasKind) {
                jdbc.execute("ALTER TABLE prf_reference_claim ADD COLUMN kind TEXT NOT NULL DEFAULT 'bar'");
                log.info("prf_reference_claim 已补列 kind（存量库迁移：既有条目视为单条）");
            }
            boolean hasGrade = refColumns.stream().anyMatch(col -> "grade".equals(col.get("name")));
            if (!hasGrade) {
                jdbc.execute("ALTER TABLE prf_reference_claim ADD COLUMN grade TEXT NOT NULL DEFAULT ''");
                log.info("prf_reference_claim 已补列 grade（存量库迁移）");
            }
        }
    }
}
