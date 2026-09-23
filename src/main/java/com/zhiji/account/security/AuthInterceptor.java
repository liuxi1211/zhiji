package com.zhiji.account.security;

import com.zhiji.account.service.TokenService;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 鉴权拦截器：照基础架构PRD 3.3 的权限矩阵实现。
 * 规则按最长前缀匹配——/api/admin/accounts/** 与 /api/admin/settings/** 仅 admin，
 * 其余 /api/admin/** 需员工会话，其余 /api/** 需客户会话；两套会话互不认对方 token（D18）。
 * BizException 在拦截器内不走 GlobalExceptionHandler，这里自行写错误体。
 */
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            return true;
        }
        // 公开白名单（基础架构PRD 3.3）：常量下发、分享、登录注册动作本身
        if (uri.startsWith("/api/constants") || uri.startsWith("/api/share/")
                || uri.equals("/api/auth/customer/login") || uri.equals("/api/auth/customer/register")
                || uri.equals("/api/auth/customer/captcha") || uri.equals("/api/auth/staff/login")) {
            return true;
        }
        String token = bearer(request);
        // 员工会话区
        if (uri.startsWith("/api/admin/accounts") || uri.startsWith("/api/admin/settings")) {
            return requireStaff(request, response, token, true);
        }
        if (uri.startsWith("/api/admin/")) {
            return requireStaff(request, response, token, false);
        }
        if (uri.equals("/api/auth/staff/me")) {
            return requireStaff(request, response, token, false);
        }
        // 其余 /api/** 均需客户会话
        return requireCustomer(response, token);
    }

    private boolean requireStaff(HttpServletRequest request, HttpServletResponse response, String token, boolean adminOnly) {
        try {
            var principal = tokenService.resolveStaff(token);
            if (adminOnly && !"admin".equals(principal.role())) {
                return reject(response, 403, "AUTH_FORBIDDEN", "没有权限执行该操作");
            }
            AuthContext.set(new AuthContext.CurrentUser(AuthContext.UserType.STAFF, principal.id(), principal.role()));
            return true;
        } catch (BizException e) {
            return reject(response, e.getStatus(), e.getCode(), e.getMessage());
        }
    }

    private boolean requireCustomer(HttpServletResponse response, String token) {
        try {
            var principal = tokenService.resolveCustomer(token);
            AuthContext.set(new AuthContext.CurrentUser(AuthContext.UserType.CUSTOMER, principal.id(), ""));
            return true;
        } catch (BizException e) {
            return reject(response, e.getStatus(), e.getCode(), e.getMessage());
        }
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw BizException.unauthorizedToken();
        }
        return header.substring(7);
    }

    private boolean reject(HttpServletResponse response, int status, String code, String message) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code
                    + "\",\"message\":\"" + message + "\"}");
        } catch (Exception e) {
            throw new IllegalStateException("写鉴权错误响应失败", e);
        }
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
