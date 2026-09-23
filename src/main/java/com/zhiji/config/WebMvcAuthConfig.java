package com.zhiji.config;

import com.zhiji.account.security.AuthInterceptor;
import com.zhiji.account.service.TokenService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层鉴权装配：拦截器注册到 /api/**（树内细分规则在拦截器里）。
 * 注意本类只依赖 TokenService，不生产其依赖的 Bean（JwtSupport / PasswordHasher
 * 在 common.security 内自注册），避免循环依赖。
 */
@Configuration
public class WebMvcAuthConfig implements WebMvcConfigurer {

    private final TokenService tokenService;

    public WebMvcAuthConfig(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(tokenService)).addPathPatterns("/api/**");
    }
}
