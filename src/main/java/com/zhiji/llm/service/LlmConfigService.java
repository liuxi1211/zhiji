package com.zhiji.llm.service;

import com.zhiji.common.config.ConfigRegistry;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.config.LlmLimitsSection;
import com.zhiji.llm.config.LlmRouteSection;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import org.springframework.stereotype.Service;

/**
 * LLM 运行参数门面：强类型读写通用配置层（sys_config 快照）。
 * 读写均委托 ConfigRegistry——load/落库/快照刷新在通用层，写后即生效无需重启；
 * saveRouteConfig/saveLimits 为类型化直写（跳过 HTTP 校验，校验在 LlmRouteSection.validate）。
 */
@Service
public class LlmConfigService {

    private final ConfigRegistry registry;

    public LlmConfigService(ConfigRegistry registry) {
        this.registry = registry;
    }

    public LlmRouteConfig routeConfig() {
        return registry.get(LlmRouteSection.KEY);
    }

    public LlmLimits limits() {
        return registry.get(LlmLimitsSection.KEY);
    }

    public RouteTarget target(LlmPurpose purpose) {
        return routeConfig().target(purpose.getCode());
    }

    public LlmRouteConfig defaultRouteConfig() {
        return registry.defaults(LlmRouteSection.KEY);
    }

    public void saveRouteConfig(LlmRouteConfig config) {
        registry.saveTyped(LlmRouteSection.KEY, config);
    }

    public void saveLimits(LlmLimits newLimits) {
        registry.saveTyped(LlmLimitsSection.KEY, newLimits);
    }
}
