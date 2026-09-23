package com.zhiji.llm.service;

import com.zhiji.llm.api.LlmConnectivityReport;
import com.zhiji.llm.api.LlmConnectivityReport.Item;
import com.zhiji.llm.config.LlmProperties;
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
    private final LlmProperties properties;
    private final LlmTransport transport;

    public LlmHealthService(LlmConfigService configService, LlmProperties properties, LlmTransport transport) {
        this.configService = configService;
        this.properties = properties;
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
        if (!properties.getProviders().containsKey(target.provider())) {
            return new Item(purposeCode, target.provider(), target.model(), target.embedding(), false,
                    "未配置的 provider（检查 zhiji.llm.providers）");
        }
        LlmProperties.Provider provider = properties.getProviders().get(target.provider());
        if (!provider.configured()) {
            return new Item(purposeCode, target.provider(), target.model(), target.embedding(), false,
                    "API Key 未配置（设置环境变量或 application.yml）");
        }
        if (target.embedding() && (target.model() == null || target.model().isBlank())) {
            return new Item(purposeCode, target.provider(), target.model(), true, false,
                    "向量化模型未配置（火山方舟 coding plan 可能不含 embedding 额度）");
        }
        try {
            if (target.embedding()) {
                transport.embed(target.provider(), target.model(), List.of("ping"));
            } else {
                transport.call(target.provider(), target.model(), 0.0, 8, null, "ping");
            }
            return new Item(purposeCode, target.provider(), target.model(), target.embedding(), true, "连通正常");
        } catch (RuntimeException e) {
            return new Item(purposeCode, target.provider(), target.model(), target.embedding(), false,
                    "调用失败：" + e.getMessage());
        }
    }

    /** 路由中出现的 provider 名集合（设置页展示 provider 下拉用）。 */
    public List<String> knownProviders() {
        return new ArrayList<>(properties.getProviders().keySet());
    }
}
