package com.zhiji.llm.service;

import com.zhiji.llm.api.LlmConnectivityReport;
import com.zhiji.llm.api.LlmConnectivityReport.Item;
import com.zhiji.llm.domain.LlmChannelConfig.Channel;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 连通性自检：对当前路由去重后的每个目标各发一次最小请求，验证 key 与网络。
 * 配置改完当场可验证（B端管理台PRD 第十节），失败给出可读原因；
 * embedding 未配置模型时标记为「未配置」而非报错崩溃。
 */
@Service
public class LlmHealthService {

    private final LlmConfigService configService;
    private final LlmChannelService channels;
    private final LlmTransport transport;

    public LlmHealthService(LlmConfigService configService, LlmChannelService channels, LlmTransport transport) {
        this.configService = configService;
        this.channels = channels;
        this.transport = transport;
    }

    public LlmConnectivityReport check() {
        LlmRouteConfig routeConfig = configService.routeConfig();
        Map<String, Item> unique = new LinkedHashMap<>();
        if (routeConfig.purposes() != null) {
            for (Map.Entry<String, RouteTarget> entry : routeConfig.purposes().entrySet()) {
                RouteTarget target = entry.getValue();
                String key = target.provider() + "::" + target.model() + "::" + target.embedding();
                // 必须用 computeIfAbsent：putIfAbsent 的实参先求值，会让每个入口都真发一次请求
                unique.computeIfAbsent(key, ignored -> probe(entry.getKey(), target));
            }
        }
        List<Item> items = new ArrayList<>(unique.values());
        boolean allOk = items.stream().allMatch(Item::ok);
        return new LlmConnectivityReport(allOk, items);
    }

    private Item probe(String purposeCode, RouteTarget target) {
        Channel channel = channels.channel(target.provider());
        if (channel == null) {
            return new Item(purposeCode, target.provider(), target.model(), target.embedding(), false,
                    "未配置的渠道（检查 LLM 管理页「渠道与模型」）");
        }
        if (!channel.configured()) {
            return new Item(purposeCode, target.provider(), target.model(), target.embedding(), false,
                    "API Key 未配置（LLM 管理页渠道卡填写后保存）");
        }
        if (target.embedding() && (target.model() == null || target.model().isBlank())) {
            return new Item(purposeCode, target.provider(), target.model(), true, false,
                    "向量化模型未配置（火山方舟 coding plan 可能不含 embedding 额度）");
        }
        try {
            if (target.embedding()) {
                transport.embed(target.provider(), target.model(), List.of("ping"));
            } else {
                transport.call(LlmCallSpec.of(target.provider(), target.model(), 0.0, 8, target.thinking(), null),
                        null, "ping");
            }
            return new Item(purposeCode, target.provider(), target.model(), target.embedding(), true, "连通正常");
        } catch (RuntimeException e) {
            return new Item(purposeCode, target.provider(), target.model(), target.embedding(), false,
                    "调用失败：" + e.getMessage());
        }
    }

    /** 路由中出现的渠道名集合（LLM 管理页展示渠道下拉用）。 */
    public List<String> knownProviders() {
        return new ArrayList<>(channels.all().channels().keySet());
    }

    /** 单渠道探测结果：模型取该渠道已保存配置的默认模型（兜底首个模型）。 */
    public record ChannelProbe(boolean ok, String detail, long latencyMs, String model) {
    }

    /**
     * 渠道级连通测试（LLM 管理页渠道卡「测试连通」按钮）：对该渠道发起一次最小真实请求。
     * 只测已保存配置（模型用 default-model，兜底 models 首个），测试的是库内现值而非页面未保存草稿。
     */
    public ChannelProbe probeChannel(String name) {
        Channel channel = channels.channel(name);
        if (channel == null) {
            return new ChannelProbe(false, "未配置的渠道", 0, null);
        }
        if (!channel.configured()) {
            return new ChannelProbe(false, "Base URL 或 API Key 未配置", 0, null);
        }
        String model = channel.defaultModel() != null && !channel.defaultModel().isBlank()
                ? channel.defaultModel()
                : channel.models().isEmpty() ? null : channel.models().get(0);
        if (model == null) {
            return new ChannelProbe(false, "该渠道未配置任何模型", 0, null);
        }
        long start = System.nanoTime();
        try {
            // thinking 显式关闭：探测只验连通，不消耗思考预算（对齐上方路由探测口径）
            transport.call(LlmCallSpec.of(name, model, 0.0, 8, false, null), null, "ping");
            return new ChannelProbe(true, "连通正常", elapsedMs(start), model);
        } catch (RuntimeException e) {
            return new ChannelProbe(false, "调用失败：" + e.getMessage(), elapsedMs(start), model);
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
