package com.zhiji.llm.service;

import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自举：sys_config 缺少运行参数时写入默认值（模型路由 + 限额），并把已有参数加载进内存快照。
 * 与 AdminBootstrap 同套路——空库也能直接可用，管理员随后在设置页调整。
 *
 * 老库路由维护两类（只动缺的与坏的，管理员显式配置的合法模型一律尊重）：
 * ① 缺新入口（入口随业务模块持续增加）→ 按默认档补齐；
 * ② 模型已下线（不在 provider.models 清单）→ 归一化到 provider 的 default-model，
 *    否则存量库在模型换代后全链路 Stream failed，只能手工清库。
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
        // load 必须先于补齐执行：routeConfig 内存快照默认为空，先补后读会把库里已配置的路由整体覆盖
        configService.load();
        if (!configService.hasKey(LlmConfigService.KEY_ROUTE)) {
            configService.saveRouteConfig(configService.defaultRouteConfig());
            log.info("已写入默认 LLM 模型路由（{}）", LlmConfigService.KEY_ROUTE);
        } else {
            fillMissingRoutes();
            normalizeRetiredModels();
        }
        if (!configService.hasKey(LlmConfigService.KEY_LIMITS)) {
            configService.saveLimits(LlmLimits.defaults());
            log.info("已写入默认 LLM 限额（{}）", LlmConfigService.KEY_LIMITS);
        }
        log.info("LLM provider 密钥就绪情况：{}（来源：config/application-local.properties 或环境变量，未配置的 provider 调不通）",
                providerKeysStatus());
    }

    /** 老库路由缺新入口时按默认档补齐缺失项，已配置的入口一律不动（管理员可能手动调过模型名）。 */
    private void fillMissingRoutes() {
        Map<String, RouteTarget> purposes = new LinkedHashMap<>();
        LlmRouteConfig defaults = configService.defaultRouteConfig();
        List<String> filled = new ArrayList<>();
        for (Map.Entry<String, RouteTarget> entry : defaults.purposes().entrySet()) {
            RouteTarget existing = configService.target(LlmPurpose.fromCode(entry.getKey()));
            if (existing == null || existing.provider() == null || existing.provider().isBlank()) {
                purposes.put(entry.getKey(), entry.getValue());
                filled.add(entry.getKey());
            }
        }
        if (!filled.isEmpty()) {
            LlmRouteConfig merged = new LlmRouteConfig(new LinkedHashMap<>(configService.routeConfig().purposes()));
            purposes.forEach(merged.purposes()::putIfAbsent);
            configService.saveRouteConfig(merged);
            log.info("老库路由缺新入口，已按默认档补齐：{}", String.join(", ", filled));
        }
    }

    /**
     * 存量路由归一化：路由模型不在该 provider 的 models 清单（yml 下线/换代）→ 换 provider 的 default-model。
     * models 清单为空的 provider 跳过（无法判断下线）；管理员在设置页把模型换成清单外新值也会被归一化——
     * 清单即事实源，支持新模型应先加进 yml。
     */
    private void normalizeRetiredModels() {
        LlmRouteConfig current = configService.routeConfig();
        if (current.purposes() == null || current.purposes().isEmpty()) {
            return;
        }
        Map<String, RouteTarget> purposes = new LinkedHashMap<>(current.purposes());
        List<String> normalized = new ArrayList<>();
        for (Map.Entry<String, RouteTarget> entry : purposes.entrySet()) {
            RouteTarget target = entry.getValue();
            if (target.provider() == null || target.model() == null || target.model().isBlank()) {
                continue;
            }
            // embedding 路由不参与归一化：它的模型不在 chat models 清单属正常（方舟 coding plan 仅 vision-embedding 可用），
            // 按清单归一化会把向量化入口错指到聊天模型
            if (target.embedding()) {
                continue;
            }
            LlmProperties.Provider provider = properties.getProviders().get(target.provider());
            if (provider == null || provider.getModels().isEmpty() || provider.getDefaultModel() == null) {
                continue;
            }
            if (!provider.getModels().contains(target.model())) {
                purposes.put(entry.getKey(), new RouteTarget(target.provider(), provider.getDefaultModel(),
                        target.temperature(), target.maxTokens(), target.embedding(), target.thinking()));
                normalized.add(entry.getKey() + "→" + provider.getDefaultModel());
            }
        }
        if (!normalized.isEmpty()) {
            configService.saveRouteConfig(new LlmRouteConfig(purposes));
            log.info("存量路由含已下线模型，已归一化到 provider 默认模型：{}", String.join(", ", normalized));
        }
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
