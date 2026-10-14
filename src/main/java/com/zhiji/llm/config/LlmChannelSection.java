package com.zhiji.llm.config;

import com.zhiji.common.config.ConfigRegistry;
import com.zhiji.common.config.ConfigSection;
import com.zhiji.common.config.FieldDef;
import com.zhiji.common.exception.BizException;
import com.zhiji.llm.domain.LlmChannelConfig;
import com.zhiji.llm.domain.LlmChannelConfig.Channel;
import com.zhiji.llm.domain.LlmRouteConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置域「模型渠道」（llm.channel）：渠道名 → 协议 / base-url / apiKey / 模型清单 / 默认模型。
 * 取代 yml zhiji.llm.providers 成为运行时事实源（LLM 管理页可改，保存即生效）——
 * yml providers 降级为首次启动种子（defaults()），存量部署升级时自动补种。
 *
 * 密钥策略（2026-10 起取代原 T2-D3「密钥不进库」决策）：apiKey 随库存储、页面脱敏展示——
 * masked() 只下发掩码（****abcd），merge() 把未修改的掩码/空值还原为库内现值，真实密钥不出后端。
 *
 * fields() 为空：本域结构（渠道 Map）由 LLM 管理页定制渲染，不走通用表单渲染器。
 * 渠道被 llm.route 引用时禁止删除（validate 挡在落库前，防路由悬空）。
 */
@Component
public class LlmChannelSection implements ConfigSection<LlmChannelConfig> {

    public static final String KEY = "llm.channel";

    /** 首启种子来源：yml providers（含环境变量解析后的密钥）。 */
    private final LlmProperties properties;

    /** ObjectProvider 打破构造环：ConfigRegistry → 本域 → ConfigRegistry；validate 运行期才解析。 */
    private final ObjectProvider<ConfigRegistry> registry;
    private final JsonMapper jsonMapper = new JsonMapper();

    public LlmChannelSection(LlmProperties properties, ObjectProvider<ConfigRegistry> registry) {
        this.properties = properties;
        this.registry = registry;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "模型渠道";
    }

    @Override
    public String description() {
        return "渠道连接、密钥与模型清单，保存后立即生效；密钥脱敏显示，留空保持不变";
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public List<FieldDef> fields() {
        // 值结构由 LLM 管理页「渠道与模型」tab 定制渲染；schema 事实源是 LlmChannelConfig record
        return List.of();
    }

    /** 首启种子：yml providers（含环境变量解析后的密钥）原样入库。 */
    @Override
    public LlmChannelConfig defaults() {
        return LlmChannelConfig.fromSeed(properties.getProviders());
    }

    @Override
    public LlmChannelConfig parse(JsonNode json) {
        return jsonMapper.treeToValue(json, LlmChannelConfig.class);
    }

    @Override
    public void validate(LlmChannelConfig config) {
        if (config.channels().isEmpty()) {
            throw BizException.validation("至少保留一个渠道");
        }
        for (Map.Entry<String, Channel> entry : config.channels().entrySet()) {
            String name = entry.getKey();
            Channel channel = entry.getValue();
            if (name == null || name.isBlank() || !name.strip().equals(name) || name.length() > 32) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "渠道名不合法：" + name);
            }
            if (channel.protocol() != null && !channel.protocol().isBlank()
                    && !List.of("openai", "anthropic").contains(channel.protocol().strip())) {
                throw BizException.of(400, "SYS_PARAM_INVALID",
                        "渠道 " + name + " 的协议必须是 openai 或 anthropic");
            }
            if (channel.baseUrl() == null || channel.baseUrl().isBlank()
                    || !(channel.baseUrl().startsWith("http://") || channel.baseUrl().startsWith("https://"))) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "渠道 " + name + " 的 Base URL 必须以 http(s):// 开头");
            }
            if (channel.defaultModel() != null && !channel.defaultModel().isBlank()
                    && !channel.models().isEmpty() && !channel.models().contains(channel.defaultModel())) {
                throw BizException.of(400, "SYS_PARAM_INVALID",
                        "渠道 " + name + " 的默认模型不在模型清单内");
            }
        }
        requireNoReferencedDeletion(config);
    }

    /** 渠道删除保护：llm.route 里引用的渠道不可在本域删掉，否则全链路调用悬空。 */
    private void requireNoReferencedDeletion(LlmChannelConfig incoming) {
        ConfigRegistry registryValue = registry.getIfAvailable();
        LlmRouteConfig route = registryValue == null ? null : registryValue.get(LlmRouteSection.KEY);
        if (route == null || route.purposes() == null) {
            return;
        }
        for (LlmRouteConfig.RouteTarget target : route.purposes().values()) {
            String provider = target == null ? null : target.provider();
            if (provider == null || provider.isBlank() || incoming.channels().containsKey(provider)) {
                continue;
            }
            throw BizException.of(400, "SYS_PARAM_INVALID",
                    "渠道 " + provider + " 正被模型路由引用，请先在「模型路由」中调整后再删除");
        }
    }

    /**
     * 下发 llm.channelUsage：渠道名 → 引用它的路由入口 codes（模型路由 llm.route 现值）。
     * 渠道卡据此显示「N 个入口在用」，未被引用的渠道显示「未被路由引用」。
     */
    @Override
    public Map<String, Object> sources() {
        Map<String, Object> sources = new LinkedHashMap<>();
        Map<String, List<String>> usage = new LinkedHashMap<>();
        ConfigRegistry registryValue = registry.getIfAvailable();
        LlmRouteConfig route = registryValue == null ? null : registryValue.get(LlmRouteSection.KEY);
        if (route != null && route.purposes() != null) {
            for (Map.Entry<String, LlmRouteConfig.RouteTarget> entry : route.purposes().entrySet()) {
                String provider = entry.getValue() == null ? null : entry.getValue().provider();
                if (provider != null && !provider.isBlank()) {
                    usage.computeIfAbsent(provider, key -> new java.util.ArrayList<>()).add(entry.getKey());
                }
            }
        }
        sources.put("llm.channelUsage", usage);
        return sources;
    }

    /** GET 下发脱敏副本；真实密钥不出后端。 */
    @Override
    public Object masked(Object value) {
        return ((LlmChannelConfig) value).masked();
    }

    /**
     * 保存合并：apiKey 为空或仍是掩码（**** 开头）= 未修改，还原为库内现值——
     * 真实密钥不可能以 **** 开头，掩码形态不构成误判。
     */
    @Override
    public Object merge(Object incoming, Object current) {
        LlmChannelConfig next = (LlmChannelConfig) incoming;
        LlmChannelConfig existing = (LlmChannelConfig) current;
        if (existing == null || existing.channels().isEmpty()) {
            return next;
        }
        Map<String, Channel> merged = new LinkedHashMap<>();
        next.channels().forEach((name, channel) -> {
            Channel old = existing.channels().get(name);
            String apiKey = channel.apiKey();
            if (old != null && (apiKey == null || apiKey.isBlank()
                    || apiKey.startsWith(LlmChannelConfig.MASK_PREFIX))) {
                apiKey = old.apiKey();
            }
            merged.put(name, new Channel(channel.protocol(), channel.baseUrl(), apiKey,
                    channel.models(), channel.defaultModel()));
        });
        return new LlmChannelConfig(merged);
    }
}
