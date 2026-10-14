package com.zhiji.config;

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
 * 全局表结构启动补丁（SQLite 无独立迁移工具，schema.sql 的 CREATE TABLE IF NOT EXISTS 改不了既有表）。
 * 只做「为存量库补列」这一件事，须早于业务 Bean 的启动校验执行（Order 最高优先级）。
 *
 * T11 学习路径与练习（4.2）：judge_session 增 owner_type（staff=试判台 / customer=练习模式），
 * 客户会话以 created_by 承载归属档案 id；存量库（试判台数据）默认回填 staff，不改变既有语义。
 *
 * T12 今日复习（4.3）：learn_state 增复习参数 4 列（due_at / review_interval_days / fail_count /
 * last_reviewed_at），存量行按 NULL/0 兜底——progressed 且 due_at NULL 视为到期（首次复测建立排期）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaPatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatcher.class);

    private final JdbcTemplate jdbc;

    public SchemaPatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        patchJudgeSessionOwnerType();
        patchLearnStateReviewColumns();
    }

    private void patchJudgeSessionOwnerType() {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(judge_session)");
        if (columns.isEmpty()) {
            return;
        }
        boolean hasOwnerType = columns.stream().anyMatch(col -> "owner_type".equals(col.get("name")));
        if (!hasOwnerType) {
            jdbc.execute("ALTER TABLE judge_session ADD COLUMN owner_type TEXT NOT NULL DEFAULT 'staff'");
            log.info("judge_session 已补列 owner_type（存量库迁移：既有试判台会话视为 staff 归属）");
        }
    }

    /** learn_state 逐列检查补列（缺哪列补哪列，幂等）。 */
    private void patchLearnStateReviewColumns() {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(learn_state)");
        if (columns.isEmpty()) {
            return;
        }
        patchColumn(columns, "learn_state", "due_at", "TEXT");
        patchColumn(columns, "learn_state", "review_interval_days", "INTEGER NOT NULL DEFAULT 0");
        patchColumn(columns, "learn_state", "fail_count", "INTEGER NOT NULL DEFAULT 0");
        patchColumn(columns, "learn_state", "last_reviewed_at", "TEXT");
    }

    private void patchColumn(List<Map<String, Object>> columns, String table, String name, String ddl) {
        boolean exists = columns.stream().anyMatch(col -> name.equals(col.get("name")));
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + ddl);
            log.info("{} 已补列 {}（存量库迁移）", table, name);
        }
    }
}
