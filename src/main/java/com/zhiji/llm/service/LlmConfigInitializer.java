package com.zhiji.llm.service;

import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmLimits;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自举：sys_config 缺少运行参数时写入默认值（模型路由 + 限额），并把已有参数加载进内存快照。
 * 与 AdminBootstrap 同套路——空库也能直接可用，管理员随后在设置页调整。
 * 顺带回显 provider 密钥就绪情况：密钥在本地文件/环境变量里配了没配，启动日志即可判断（只报有无，不报值）。
 */
@Component
public class LlmConfigInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigInitializer.class);

    private final LlmConfigService configService;
    private final LlmProperties properties;

    public LlmConfigInitializer(LlmConfigService configService, LlmProperties properties) {
        this.configService = configService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!configService.hasKey(LlmConfigService.KEY_ROUTE)) {
            configService.saveRouteConfig(LlmConfigService.defaultRouteConfig());
            log.info("已写入默认 LLM 模型路由（{}）", LlmConfigService.KEY_ROUTE);
        }
        if (!configService.hasKey(LlmConfigService.KEY_LIMITS)) {
            configService.saveLimits(LlmLimits.defaults());
            log.info("已写入默认 LLM 限额（{}）", LlmConfigService.KEY_LIMITS);
        }
        configService.load();
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