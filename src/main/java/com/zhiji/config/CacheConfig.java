package com.zhiji.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地缓存配置（不引 Redis）。
 * 考点库是"读多写少、内容稳定"的典型，用 Caffeine 做进程内缓存即可。
 */
@Configuration
public class CacheConfig {

    public static final int MAX_SIZE = 1000;
    public static final int TTL_MINUTES = 30;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(TTL_MINUTES, TimeUnit.MINUTES)
                .recordStats());
        return manager;
    }
}