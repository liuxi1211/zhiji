package com.zhiji.common.security;

/**
 * 当前操作人上下文（用户概念）：拦截器鉴权后注入，业务写接口随时可取"是谁在操作"。
 * 审计日志表按 D19 触发条件①再加，操作人取用能力从第一天就有。
 */
public final class AuthContext {

    public enum UserType {
        STAFF, CUSTOMER
    }

    public record CurrentUser(UserType type, long id, String role) {
    }

    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(CurrentUser user) {
        CURRENT.set(user);
    }

    public static CurrentUser get() {
        return CURRENT.get();
    }

    public static Long staffId() {
        CurrentUser user = CURRENT.get();
        return user != null && user.type() == UserType.STAFF ? user.id() : null;
    }

    public static String staffRole() {
        CurrentUser user = CURRENT.get();
        return user != null && user.type() == UserType.STAFF ? user.role() : null;
    }

    public static Long customerId() {
        CurrentUser user = CURRENT.get();
        return user != null && user.type() == UserType.CUSTOMER ? user.id() : null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
