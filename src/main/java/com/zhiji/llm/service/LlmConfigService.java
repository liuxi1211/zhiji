package com.zhiji.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import com.zhiji.llm.domain.SysConfig;
import com.zhiji.llm.repo.SysConfigMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 运行参数读写（sys_config）：模型路由 llm.route 与限额 llm.limits。
 * 维护内存快照，写后刷新——B 端设置页改完即生效，无需重启。
 */
@Service
public class LlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigService.class);

    public static final String KEY_ROUTE = "llm.route";
    public static final String KEY_LIMITS = "llm.limits";

    private final SysConfigMapper configMapper;
    private final JsonMapper jsonMapper = new JsonMapper();

    private volatile LlmRouteConfig routeConfig = new LlmRouteConfig(Map.of());
    private volatile LlmLimits limits = LlmLimits.defaults();

    public LlmConfigService(SysConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    public void load() {
        LlmRouteConfig route = readJson(KEY_ROUTE, LlmRouteConfig.class);
        if (route != null) {
            routeConfig = route;
        }
        LlmLimits loaded = readJson(KEY_LIMITS, LlmLimits.class);
        if (loaded != null) {
            limits = loaded;
        }
        log.info("LLM 运行参数已加载：路由 {} 个入口，限额 judge={}/variant={}/profile={}/site={}",
                routeConfig.purposes() == null ? 0 : routeConfig.purposes().size(),
                limits.judgeDaily(), limits.variantRewriteDaily(), limits.perProfileDailyTotal(),
                limits.siteDailyTotal());
    }

    public LlmRouteConfig routeConfig() {
        return routeConfig;
    }

    public LlmLimits limits() {
        return limits;
    }

    public RouteTarget target(LlmPurpose purpose) {
        return routeConfig.target(purpose.getCode());
    }

    public void saveRouteConfig(LlmRouteConfig config) {
        writeJson(KEY_ROUTE, config);
        routeConfig = config;
    }

    public void saveLimits(LlmLimits newLimits) {
        writeJson(KEY_LIMITS, newLimits);
        limits = newLimits;
    }

    public boolean hasKey(String key) {
        return configMapper.selectCount(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key)) > 0;
    }

    public void writeJson(String key, Object value) {
        String json = jsonMapper.writeValueAsString(value);
        if (configMapper.updateValue(key, json) == 0) {
            insertNew(key, json);
        }
    }

    private void insertNew(String key, String json) {
        SysConfig config = new SysConfig();
        config.setConfigKey(key);
        config.setConfigValue(json);
        config.setDescription(description(key));
        configMapper.insert(config);
    }

    private String description(String key) {
        if (KEY_ROUTE.equals(key)) {
            return "模型路由：按用途映射 provider/模型/温度";
        }
        if (KEY_LIMITS.equals(key)) {
            return "LLM 限额与全站每日熔断阈值";
        }
        return key;
    }

    private <T> T readJson(String key, Class<T> type) {
        SysConfig row = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(row.getConfigValue(), type);
        } catch (JacksonException e) {
            log.warn("运行参数 {} 解析失败，使用默认值：{}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 默认路由：按默认档给一份可直接运行的种子配置（管理员可在设置页改）。
     * 模型名用日期快照——判定链路的稳定性依赖模型版本不漂移；
     * embedding 用 vision-embedding：方舟 coding plan 拒绝纯文本 embedding 模型（UnsupportedModel），实测唯其可用。
     */
    public static LlmRouteConfig defaultRouteConfig() {
        Map<String, RouteTarget> purposes = new LinkedHashMap<>();
        for (LlmPurpose purpose : LlmPurpose.values()) {
            switch (purpose.tier()) {
                case JUDGE -> purposes.put(purpose.getCode(),
                        new RouteTarget("ark", "doubao-seed-2-0-lite-260428", 0.0, 2048, false));
                case QUALITY -> purposes.put(purpose.getCode(),
                        new RouteTarget("ark", "doubao-seed-2-1-turbo-260628", null, 4096, false));
                case EMBED -> purposes.put(purpose.getCode(),
                        new RouteTarget("ark", "doubao-embedding-vision-251215", null, null, true));
            }
        }
        return new LlmRouteConfig(purposes);
    }
}
