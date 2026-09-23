package com.zhiji.config;

import com.zhiji.common.exception.BizException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * 实测发现的关键差异（SQLite 与 MySQL 不一样）：
 * SQLite 驱动抛约束异常时 SQL state 为 null，Spring 无法归类，
 * 于是 SQLITE_CONSTRAINT_* 不会走 DataIntegrityViolationException，
 * 而是落到 UncategorizedSQLException。必须单独接住，否则用户看到的是 500。
 *
 * 处理原则与架构文档 6.4 的"约束执行分工"一致：
 * 应用层（Bean Validation）是第一道防线，数据库约束是最后一道，
 * 但数据库的错误必须被翻译成 4xx，不能漏成 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException e) {
        Map<String, Object> body = base(HttpStatus.valueOf(e.getStatus()), e.getCode(), e.getMessage());
        if (e.getFields() != null) {
            body.put("fields", e.getFields());
        }
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        Map<String, Object> body = base(HttpStatus.BAD_REQUEST, "COMMON_VALIDATION", "参数校验未通过");
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException e) {
        return build(HttpStatus.CONFLICT, "COMMON_CONFLICT", "记录已存在（唯一约束冲突）");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException e) {
        return translate(e.getMostSpecificCause() == null ? "" : e.getMostSpecificCause().getMessage());
    }

    /** SQLite 的约束异常走这里（SQL state 为 null，Spring 无法归类）。 */
    @ExceptionHandler(UncategorizedSQLException.class)
    public ResponseEntity<Map<String, Object>> handleUncategorized(UncategorizedSQLException e) {
        return translate(e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage());
    }

    private ResponseEntity<Map<String, Object>> translate(String raw) {
        String msg = raw == null ? "" : raw;
        if (msg.contains("SQLITE_CONSTRAINT_CHECK") || msg.contains("CHECK constraint failed")) {
            return build(HttpStatus.BAD_REQUEST, "COMMON_CHECK_VIOLATION", "字段取值不在允许范围内（CHECK 约束）");
        }
        if (msg.contains("SQLITE_CONSTRAINT_UNIQUE") || msg.contains("UNIQUE constraint failed")) {
            return build(HttpStatus.CONFLICT, "COMMON_CONFLICT", "记录已存在（唯一约束冲突）");
        }
        if (msg.contains("SQLITE_CONSTRAINT_FOREIGNKEY") || msg.contains("FOREIGN KEY constraint failed")) {
            return build(HttpStatus.BAD_REQUEST, "COMMON_FOREIGN_KEY", "引用了不存在的记录（外键约束）");
        }
        if (msg.contains("SQLITE_CONSTRAINT_NOTNULL")) {
            return build(HttpStatus.BAD_REQUEST, "COMMON_NOT_NULL", "必填字段不能为空");
        }
        return build(HttpStatus.BAD_REQUEST, "COMMON_CONSTRAINT", "数据不满足约束");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status).body(base(status, code, msg));
    }

    private Map<String, Object> base(HttpStatus status, String code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.value());
        m.put("code", code);
        m.put("message", msg);
        return m;
    }
}
