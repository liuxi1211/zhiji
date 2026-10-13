package com.zhiji.llm.config;

import com.zhiji.common.config.ConfigSection;
import com.zhiji.common.config.FieldDef;
import com.zhiji.common.config.FieldType;
import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置域「模型路由」（llm.route）：按用途映射 provider/模型/温度/TOKEN 上限/思考模式。
 * 前端渲染为固定行表格（行 = LlmPurpose 常量组 18 入口），provider 与模型下拉取 yml 事实源。
 * 启动修复两类老库数据（只动缺的与坏的，管理员显式配置的合法模型一律尊重）：
 * ① 缺新入口（入口随业务模块持续增加）→ 按默认档补齐；
 * ② 模型已下线（不在 provider.models 清单）→ 归一化到 provider 的 default-model，
 *    否则存量库在模型换代后全链路 Stream failed，只能手工清库。
 */
@Component
public class LlmRouteSection implements ConfigSection<LlmRouteConfig> {

    private static final Logger log = LoggerFactory.getLogger(LlmRouteSection.class);

    public static final String KEY = "llm.route";

    private final LlmProperties properties;
    private final JsonMapper jsonMapper = new JsonMapper();

    public LlmRouteSection(LlmProperties properties) {
        this.properties = properties;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "模型路由";
    }

    @Override
    public String description() {
        return "按用途选择 provider 与模型";
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public List<FieldDef> fields() {
        return List.of(FieldDef.of("purposes", "路由表", FieldType.TABLE)
                .rowKeySource("constants:llm_purpose")
                .columns(List.of(
                        FieldDef.of("provider", "Provider", FieldType.SELECT)
                                .optionsSource("llm.providers").width(150).build(),
                        FieldDef.of("model", "模型名", FieldType.SELECT)
                                .optionsSource("llm.providerModels").optionsDependsOn("provider")
                                .creatable().minWidth(200).build(),
                        FieldDef.of("temperature", "温度", FieldType.NUMBER)
                                .min(0).max(2).step(0.1).width(110).build(),
                        FieldDef.of("maxTokens", "最大 Token", FieldType.NUMBER)
                                .min(1).width(130).build(),
                        FieldDef.of("embedding", "向量化", FieldType.TAG)
                                .tagText("embedding").width(90).build(),
                        FieldDef.of("thinking", "思考模式", FieldType.SELECT)
                                .triState("跟随默认")
                                .options(List.of(new FieldDef.Option(true, "开启"), new FieldDef.Option(false, "关闭")))
                                .visibleWhen("embedding", "ne", true).width(120).build()))
                .build());
    }

    /**
     * 默认路由：按默认档给一份可直接运行的种子配置（管理员可在设置页改）。
     * 模型名取 provider 配置的 default-model（application.yml 维护，模型换代改 yml 即可）——
     * 不再硬编码日期快照：快照模型下线后种子配置直接不可用（Stream failed 即此症状）。
     * embedding 单列：方舟 coding plan 拒绝纯文本 embedding 模型（UnsupportedModel），实测唯 vision-embedding 可用。
     */
    @Override
    public LlmRouteConfig defaults() {
        String arkModel = defaultModelOf("ark");
        Map<String, RouteTarget> purposes = new LinkedHashMap<>();
        for (LlmPurpose purpose : LlmPurpose.values()) {
            switch (purpose.tier()) {
                case JUDGE -> purposes.put(purpose.getCode(),
                        new RouteTarget("ark", arkModel, 0.0, 2048, false));
                case QUALITY -> purposes.put(purpose.getCode(),
                        new RouteTarget("ark", arkModel, null, 4096, false));
                case EMBED -> purposes.put(purpose.getCode(),
                        new RouteTarget("ark", "doubao-embedding-vision-251215", null, null, true));
            }
        }
        return new LlmRouteConfig(purposes);
    }

    @Override
    public LlmRouteConfig parse(JsonNode json) {
        return jsonMapper.treeToValue(json, LlmRouteConfig.class);
    }

    /** 未知入口 / 缺目标 / 未配置的 provider / 文本入口缺模型名 / 模型不在 provider 清单一律挡在落库前。 */
    @Override
    public void validate(LlmRouteConfig config) {
        if (config.purposes() == null || config.purposes().isEmpty()) {
            throw BizException.validation("路由不能为空");
        }
        for (Map.Entry<String, RouteTarget> entry : config.purposes().entrySet()) {
            LlmPurpose purpose = parsePurpose(entry.getKey());
            RouteTarget target = entry.getValue();
            if (target == null) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "入口缺少路由目标：" + entry.getKey());
            }
            if (target.provider() == null || !properties.getProviders().containsKey(target.provider())) {
                throw BizException.of(400, "SYS_PARAM_INVALID",
                        "入口 " + entry.getKey() + " 指向未配置的 provider：" + target.provider());
            }
            if (!target.embedding() && (target.model() == null || target.model().isBlank())) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "入口缺少模型名：" + entry.getKey());
            }
            // 模型必须在 provider 的 models 清单内（yml 事实源）：防止手输错模型名把错误留到真实调用才暴露
            if (!target.embedding()) {
                List<String> models = properties.getProviders().get(target.provider()).getModels();
                if (models != null && !models.isEmpty() && !models.contains(target.model())) {
                    throw BizException.of(400, "SYS_PARAM_INVALID",
                            "入口 " + entry.getKey() + " 的模型 " + target.model() + " 不在 provider "
                                    + target.provider() + " 的支持清单内");
                }
            }
        }
    }

    @Override
    public Object afterLoad(Object loaded) {
        LlmRouteConfig current = (LlmRouteConfig) loaded;
        LlmRouteConfig filled = fillMissingRoutes(current);
        return normalizeRetiredModels(filled);
    }

    @Override
    public Map<String, Object> sources() {
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("llm.providers", new ArrayList<>(properties.getProviders().keySet()));
        Map<String, List<String>> models = new LinkedHashMap<>();
        properties.getProviders().forEach((name, provider) -> models.put(name, provider.getModels()));
        sources.put("llm.providerModels", models);
        return sources;
    }

    /** 老库路由缺新入口时按默认档补齐缺失项，已配置的入口一律不动（管理员可能手动调过模型名）。 */
    private LlmRouteConfig fillMissingRoutes(LlmRouteConfig current) {
        Map<String, RouteTarget> purposes = new LinkedHashMap<>(
                current.purposes() == null ? Map.of() : current.purposes());
        List<String> filled = new ArrayList<>();
        for (Map.Entry<String, RouteTarget> entry : defaults().purposes().entrySet()) {
            RouteTarget existing = purposes.get(entry.getKey());
            if (existing == null || existing.provider() == null || existing.provider().isBlank()) {
                purposes.put(entry.getKey(), entry.getValue());
                filled.add(entry.getKey());
            }
        }
        if (!filled.isEmpty()) {
            log.info("老库路由缺新入口，已按默认档补齐：{}", String.join(", ", filled));
        }
        return new LlmRouteConfig(purposes);
    }

    /**
     * 存量路由归一化：路由模型不在该 provider 的 models 清单（yml 下线/换代）→ 换 provider 的 default-model。
     * models 清单为空的 provider 跳过（无法判断下线）；管理员在设置页把模型换成清单外新值也会被归一化——
     * 清单即事实源，支持新模型应先加进 yml。
     */
    private LlmRouteConfig normalizeRetiredModels(LlmRouteConfig current) {
        if (current.purposes() == null || current.purposes().isEmpty()) {
            return current;
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
            log.info("存量路由含已下线模型，已归一化到 provider 默认模型：{}", String.join(", ", normalized));
            return new LlmRouteConfig(purposes);
        }
        return current;
    }

    private String defaultModelOf(String provider) {
        LlmProperties.Provider target = properties.getProviders().get(provider);
        return target != null && target.getDefaultModel() != null && !target.getDefaultModel().isBlank()
                ? target.getDefaultModel()
                : null;
    }

    private LlmPurpose parsePurpose(String code) {
        try {
            return LlmPurpose.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw BizException.of(400, "SYS_PARAM_INVALID", "未知 LLM 入口：" + code);
        }
    }
}
