package com.zhiji.llm.service;

import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.api.LlmPurpose;
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
        patchMissingRoutes();
        log.info("LLM provider 密钥就绪情况：{}（来源：config/application-local.properties 或环境变量，未配置的 provider 调不通）",
                providerKeysStatus());
    }

    /**
     * 补齐存量路由里缺失的入口：sys_config 的 llm.route 是一份 JSON，新增 purpose（随任务扩展）
     * 不会自动出现在旧库里，target() 返回 null 会让调用直接 500——启动时按默认档补齐并写回。
     */
    private void patchMissingRoutes() {
        var routeConfig = configService.routeConfig();
        var missing = new java.util.LinkedHashMap<String, LlmRouteConfig.RouteTarget>();
        for (LlmPurpose purpose : LlmPurpose.values()) {
            if (routeConfig.target(purpose.getCode()) == null) {
                missing.put(purpose.getCode(), LlmConfigService.defaultRouteConfig()
                        .purposes().get(purpose.getCode()));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        var patched = new java.util.LinkedHashMap<>(routeConfig.purposes());
        patched.putAll(missing);
        configService.saveRouteConfig(new LlmRouteConfig(patched));
        log.info("已补齐 {} 个新增 LLM 入口的默认路由：{}", missing.size(), String.join(", ", missing.keySet()));
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