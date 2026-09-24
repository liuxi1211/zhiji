package com.zhiji.common.exception;

import java.util.Map;

/**
 * 业务异常：service 层抛出，GlobalExceptionHandler 统一翻译成错误体。
 * code 遵循基础架构PRD 的分段规范（AUTH_* / COMMON_* / KB_* ...）。
 */
public class BizException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, String> fields;

    public BizException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = null;
    }

    public BizException(int status, String code, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    public static BizException of(int status, String code, String message) {
        return new BizException(status, code, message);
    }

    public static BizException of(int status, String code, String message, Map<String, String> fields) {
        return new BizException(status, code, message, fields);
    }

    public static BizException unauthorizedToken() {
        return new BizException(401, "AUTH_TOKEN_INVALID", "登录状态无效或已过期，请重新登录");
    }

    public static BizException forbidden() {
        return new BizException(403, "AUTH_FORBIDDEN", "没有权限执行该操作");
    }

    public static BizException notFound(String message) {
        return new BizException(404, "COMMON_NOT_FOUND", message);
    }

    public static BizException conflict(String code, String message) {
        return new BizException(409, code, message);
    }

    public static BizException validation(String message) {
        return new BizException(400, "COMMON_VALIDATION", message);
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getFields() {
        return fields;
    }
}
