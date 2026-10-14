package com.zhiji.config;

import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用工具 Bean（T12）：Random 供加权抽题与抽查触发注入（ReviewService），
 * 测试可通过 @Primary 覆盖为固定序列实现确定性断言。
 */
@Configuration
public class UtilConfig {

    @Bean
    public Random random() {
        return new Random();
    }
}
