package com.zhiji.llm.service;

import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmRouteConfig;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * LLM 启动诊断（ConfigRegistry.load 之后执行）：回显运行参数摘要与 provider 密钥就绪情况——
 * 密钥在本地文件/环境变量里配了没配，启动日志即可判断（只报有无，不报值）。
 * 配置的种子写入与老库修复已收拢进 LlmRouteSection / LlmLimitsSection（afterLoad）。
 */
@Component
public class LlmConfigInitializer implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigInitializer.class);

    private final LlmConfigService configService;
    private final LlmProperties properties;

    public LlmConfigInitializer(LlmConfigService configService, LlmProperties properties) {
        this.configService = configService;
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public void run(ApplicationArguments args) {
        LlmRouteConfig route = configService.routeConfig();
        var limits = configService.limits();
        log.info("LLM 运行参数已加载：路由 {} 个入口，限额 judge={}/variant={}/profile={}/site={}",
                route == null || route.purposes() == null ? 0 : route.purposes().size(),
                limits.judgeDaily(), limits.variantRewriteDaily(), limits.perProfileDailyTotal(),
                limits.siteDailyTotal());
        log.info("LLM provider 密钥就绪情况：{}（来源：config/application-local.properties 或环境变量，未配置的 provider 调不通）",
                providerKeysStatus());
    }

    private String providerKeysStatus() {
        if (properties.getProviders().isEmpty()) {
            return "无 provider";
        }
        return properties.getProviders().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + (entry.getValue().configured() ? "已配置" : "未配置"))
                .collect(Collectors.joining(", "));
    }
}
