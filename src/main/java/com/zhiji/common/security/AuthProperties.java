package com.zhiji.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证相关运行参数。jwt-secret 生产环境必须更换；验证码可按环境关闭（测试用）。
 * 放在 common.security 而非 config：jwt/验证码/拦截器等基建类都在本包，配置随之同包避免反向依赖。
 */
@ConfigurationProperties(prefix = "zhiji.auth")
public class AuthProperties {

    private String jwtSecret = "dev-secret-change-me-in-production";
    private int tokenTtlDays = 7;
    private int captchaTtlMinutes = 5;
    private boolean captchaEnabled = true;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public int getTokenTtlDays() {
        return tokenTtlDays;
    }

    public void setTokenTtlDays(int tokenTtlDays) {
        this.tokenTtlDays = tokenTtlDays;
    }

    public int getCaptchaTtlMinutes() {
        return captchaTtlMinutes;
    }

    public void setCaptchaTtlMinutes(int captchaTtlMinutes) {
        this.captchaTtlMinutes = captchaTtlMinutes;
    }

    public boolean isCaptchaEnabled() {
        return captchaEnabled;
    }

    public void setCaptchaEnabled(boolean captchaEnabled) {
        this.captchaEnabled = captchaEnabled;
    }
}
