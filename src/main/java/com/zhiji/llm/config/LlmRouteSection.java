package com.zhiji.llm.config;

import com.zhiji.common.config.ConfigSection;
import com.zhiji.common.config.FieldDef;
import com.zhiji.common.config.FieldType;
import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmChannelConfig;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import com.zhiji.llm.service.LlmChannelService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置域「模型路由」（llm.route）：按用途映射渠道/模型/思考模式。
 * 温度与最大 Token 不在路由管理——请求未覆盖时不传，直接用模型默认值（长输出任务走请求级覆盖）。
 * 前端渲染为固定行表格（行 = LlmPurpose 常量组 19 入口），渠道与模型下拉取渠道库事实源（llm.channel）。
 * 启动修复两类老库数据（只动缺的与坏的，管理员显式配置的合法模型一律尊重）：
 * ① 缺新入口（入口随业务模块持续增加）→ 按默认档补齐；
 * ② 模型已下线（不在渠道 models 清单）→ 归一化到渠道的 default-model，
 *    否则存量库在模型换代后全链路 Stream failed，只能手工清库。
 *
 * ObjectProvider 打破构造环（ConfigRegistry → 本域 → LlmChannelService → ConfigRegistry），
 * 运行期才解析渠道服务。
 */
@Component
public class LlmRouteSection implements ConfigSection<LlmRouteConfig> {

    private static final Logger log = LoggerFactory.getLogger(LlmRouteSection.class);

    public static final String KEY = "llm.route";

    private final ObjectProvider<LlmChannelService> channels;
    private final JsonMapper jsonMapper = new JsonMapper();

    public LlmRouteSection(ObjectProvider<LlmChannelService> channels) {
        this.channels = channels;
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
        return "按用途选择渠道与模型";
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
                                .optionsSource("llm.providers").minWidth(180).build(),
                        FieldDef.of("model", "模型名", FieldType.SELECT)
                                .optionsSource("llm.providerModels").optionsDependsOn("provider")
                                .creatable().minWidth(300).build(),
                        FieldDef.of("embedding", "向量化", FieldType.TAG)
                                .tagText("embedding").width(100).build(),
                        FieldDef.of("thinking", "思考模式", FieldType.SELECT)
                                .triState("跟随默认")
                                .options(List.of(new FieldDef.Option(true, "开启"), new FieldDef.Option(false, "关闭")))
                                .visibleWhen("embedding", "ne", true).minWidth(150).build()))
                .build());
    }

    /**
     * 默认路由：按默认档给一份可直接运行的种子配置（管理员可在 LLM 管理页改）。
     * 模型名取渠道配置的 default-model（模型换代在渠道卡改默认模型即可）——
     * 不再硬编码日期快照：快照模型下线后种子配置直接不可用（Stream failed 即此症状）。
     * 文本档走 jlc（公司代理渠道，当前主力 key）；embedding 单列：jlc 无 embedding 端点，
     * 向量化仍指向 ark（vision-embedding，方舟 coding plan 实测唯 vision-embedding 可用）。
     */
    @Override
    public LlmRouteConfig defaults() {
        String chatModel = defaultModelOf("jlc");
        if (chatModel == null) {
            chatModel = defaultModelOf("ark");
        }
        Map<String, RouteTarget> purposes = new LinkedHashMap<>();
        for (LlmPurpose purpose : LlmPurpose.values()) {
            switch (purpose.tier()) {
                case JUDGE, QUALITY -> purposes.put(purpose.getCode(), new RouteTarget("jlc", chatModel, false));
                case EMBED -> purposes.put(purpose.getCode(),
                        new RouteTarget("ark", "doubao-embedding-vision-251215", true));
            }
        }
        return new LlmRouteConfig(purposes);
    }

    @Override
    public LlmRouteConfig parse(JsonNode json) {
        return jsonMapper.treeToValue(json, LlmRouteConfig.class);
    }

    /** 未知入口 / 缺目标 / 未配置的渠道 / 文本入口缺模型名 / 模型不在渠道清单一律挡在落库前。 */
    @Override
    public void validate(LlmRouteConfig config) {
        LlmChannelConfig channelsValue = channelService().all();
        if (config.purposes() == null || config.purposes().isEmpty()) {
            throw BizException.validation("路由不能为空");
        }
        for (Map.Entry<String, RouteTarget> entry : config.purposes().entrySet()) {
            LlmPurpose purpose = parsePurpose(entry.getKey());
            RouteTarget target = entry.getValue();
            if (target == null) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "入口缺少路由目标：" + entry.getKey());
            }
            if (target.provider() == null || !channelsValue.channels().containsKey(target.provider())) {
                throw BizException.of(400, "SYS_PARAM_INVALID",
                        "入口 " + entry.getKey() + " 指向未配置的渠道：" + target.provider());
            }
            if (!target.embedding() && (target.model() == null || target.model().isBlank())) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "入口缺少模型名：" + entry.getKey());
            }
            // 模型必须在渠道的 models 清单内（渠道卡事实源）：防止手输错模型名把错误留到真实调用才暴露
            if (!target.embedding()) {
                List<String> models = channelsValue.channels().get(target.provider()).models();
                if (models != null && !models.isEmpty() && !models.contains(target.model())) {
                    throw BizException.of(400, "SYS_PARAM_INVALID",
                            "入口 " + entry.getKey() + " 的模型 " + target.model() + " 不在渠道 "
                                    + target.provider() + " 的支持清单内");
                }
            }
        }
    }

    @Override
    public Object afterLoad(Object loaded) {
        LlmRouteConfig current = (LlmRouteConfig) loaded;
        LlmRouteConfig pruned = pruneUnknownPurposes(current);
        LlmRouteConfig filled = fillMissingRoutes(pruned);
        LlmRouteConfig migrated = migrateRetiredProviders(filled);
        return normalizeRetiredModels(migrated);
    }

    /** 枚举瘦身自愈：存量库残留的未知入口（枚举项已删除）剔除——否则整体保存会被 validate 挡住。 */
    private LlmRouteConfig pruneUnknownPurposes(LlmRouteConfig current) {
        if (current.purposes() == null || current.purposes().isEmpty()) {
            return current;
        }
        List<String> pruned = current.purposes().keySet().stream()
                .filter(code -> !LlmPurpose.isValid(code))
                .toList();
        if (pruned.isEmpty()) {
            return current;
        }
        Map<String, RouteTarget> purposes = new LinkedHashMap<>(current.purposes());
        pruned.forEach(purposes::remove);
        log.info("存量路由含已下线入口，已剔除：{}", String.join(", ", pruned));
        return new LlmRouteConfig(purposes);
    }

    @Override
    public Map<String, Object> sources() {
        LlmChannelConfig channelsValue = channelService().all();
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("llm.providers", new ArrayList<>(channelsValue.channels().keySet()));
        Map<String, List<String>> models = new LinkedHashMap<>();
        channelsValue.channels().forEach((name, channel) -> models.put(name, channel.models()));
        sources.put("llm.providerModels", models);
        return sources;
    }

    private LlmChannelService channelService() {
        return channels.getObject();
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
     * 渠道迁移：存量文本路由指向 ark（火山方舟，key 过期后不可用）→ 迁到 jlc（公司代理渠道）。
     * embedding 路由不动（jlc 无 embedding 端点，向量化仍走 ark）。
     * 模型名在 jlc 清单内则保留，否则由 normalizeRetiredModels 归一化到 jlc 默认模型。
     */
    private LlmRouteConfig migrateRetiredProviders(LlmRouteConfig current) {
        LlmChannelConfig.Channel jlc = channelService().channel("jlc");
        if (jlc == null || !jlc.configured() || current.purposes() == null || current.purposes().isEmpty()) {
            return current;
        }
        Map<String, RouteTarget> purposes = new LinkedHashMap<>(current.purposes());
        List<String> migrated = new ArrayList<>();
        for (Map.Entry<String, RouteTarget> entry : purposes.entrySet()) {
            RouteTarget target = entry.getValue();
            if (!"ark".equals(target.provider()) || target.embedding()) {
                continue;
            }
            String model = jlc.models().contains(target.model()) ? target.model() : jlc.defaultModel();
            purposes.put(entry.getKey(), new RouteTarget("jlc", model,
                    target.embedding(), target.thinking()));
            migrated.add(entry.getKey() + ":ark→jlc");
        }
        if (!migrated.isEmpty()) {
            log.info("存量路由指向已弃用渠道 ark，已迁移到 jlc：{}", String.join(", ", migrated));
            return new LlmRouteConfig(purposes);
        }
        return current;
    }

    /**
     * 存量路由归一化：路由模型不在该渠道的 models 清单（渠道卡下线/换代）→ 换渠道的 default-model。
     * models 清单为空的渠道跳过（无法判断下线）；管理员在渠道卡把模型换成清单外新值也会被归一化——
     * 清单即事实源，支持新模型应先加进渠道模型清单。
     */
    private LlmRouteConfig normalizeRetiredModels(LlmRouteConfig current) {
        if (current.purposes() == null || current.purposes().isEmpty()) {
            return current;
        }
        LlmChannelConfig channelsValue = channelService().all();
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
            LlmChannelConfig.Channel provider = channelsValue.channels().get(target.provider());
            if (provider == null || provider.models().isEmpty() || provider.defaultModel() == null) {
                continue;
            }
            if (!provider.models().contains(target.model())) {
                purposes.put(entry.getKey(), new RouteTarget(target.provider(), provider.defaultModel(),
                        target.embedding(), target.thinking()));
                normalized.add(entry.getKey() + "→" + provider.defaultModel());
            }
        }
        if (!normalized.isEmpty()) {
            log.info("存量路由含已下线模型，已归一化到渠道默认模型：{}", String.join(", ", normalized));
            return new LlmRouteConfig(purposes);
        }
        return current;
    }

    private String defaultModelOf(String provider) {
        LlmChannelConfig.Channel target = channelService().channel(provider);
        return target != null && target.defaultModel() != null && !target.defaultModel().isBlank()
                ? target.defaultModel()
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
