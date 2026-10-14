package com.zhiji.llm.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 调用编排（LlmClient 的实现）：路由解析 → 限额守卫（按侧别分策略）→ 参数合并 →
 * 网络调用 → JSON 校验与重试 → 计数落库。
 * 业务模块只依赖 LlmClient，不感知渠道、模型名与重试细节；
 * 请求级覆盖（模型/温度/token/思考/超时）只影响单次调用，不写回路由配置。
 */
@Service
public class LlmGatewayService implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayService.class);

    private final LlmConfigService configService;
    private final LlmChannelService channels;
    private final LlmRateGuard rateGuard;
    private final LlmUsageService usageService;
    private final LlmTransport transport;
    private final JsonMapper jsonMapper = new JsonMapper();

    public LlmGatewayService(LlmConfigService configService, LlmChannelService channels, LlmRateGuard rateGuard,
            LlmUsageService usageService, LlmTransport transport) {
        this.configService = configService;
        this.channels = channels;
        this.rateGuard = rateGuard;
        this.usageService = usageService;
        this.transport = transport;
    }

    @Override
    public String complete(LlmRequest request) {
        LlmCallSpec spec = requireSpec(request);
        rateGuard.check(request.purpose(), request.profileKey());
        try {
            String text = transport.call(spec, request.systemPrompt(), request.userPrompt());
            usageService.recordCall(request.purpose(), request.profileKey());
            return text;
        } catch (RuntimeException e) {
            usageService.recordFail(request.purpose(), request.profileKey());
            throw providerError(e);
        }
    }

    @Override
    public String streamComplete(LlmRequest request, Consumer<String> onDelta) {
        LlmCallSpec spec = requireSpec(request);
        rateGuard.check(request.purpose(), request.profileKey());
        try {
            String text = transport.stream(spec, request.systemPrompt(), request.userPrompt(), onDelta);
            usageService.recordCall(request.purpose(), request.profileKey());
            return text;
        } catch (RuntimeException e) {
            usageService.recordFail(request.purpose(), request.profileKey());
            throw providerError(e);
        }
    }

    @Override
    public <T> T completeJson(LlmRequest request, Class<T> type) {
        LlmCallSpec spec = requireSpec(request);
        rateGuard.check(request.purpose(), request.profileKey());

        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw = null;
            try {
                raw = transport.call(spec, request.systemPrompt(), request.userPrompt());
                usageService.recordCall(request.purpose(), request.profileKey());
                return parseJson(raw, type);
            } catch (JacksonException e) {
                last = providerError(e);
                log.warn("LLM 返回非法 JSON（第 {} 次），重试：{} | 原文：{}", attempt + 1, e.getMessage(), preview(raw));
            } catch (RuntimeException e) {
                last = providerError(e);
                break;
            }
        }
        // 同一逻辑请求两次尝试都失败只记一次失败：call 计两次、fail 计一次，用量视图按此口径读
        usageService.recordFail(request.purpose(), request.profileKey());
        throw last != null ? last
                : BizException.of(502, "SYS_PROVIDER_ERROR", "模型返回内容无法解析");
    }

    @Override
    public List<float[]> embedAll(LlmPurpose purpose, Long profileId, List<String> texts) {
        RouteTarget target = requireTarget(purpose);
        if (!target.embedding()) {
            throw BizException.of(500, "SYS_PROVIDER_ERROR", "入口未配置为向量化路由: " + purpose.getCode());
        }
        long profileKey = profileId == null ? 0L : profileId;
        rateGuard.check(purpose, profileKey);
        try {
            List<float[]> vectors = transport.embed(target.provider(), target.model(), texts);
            usageService.recordCall(purpose, profileKey);
            return vectors;
        } catch (RuntimeException e) {
            usageService.recordFail(purpose, profileKey);
            throw providerError(e);
        }
    }

    private RouteTarget requireTarget(LlmPurpose purpose) {
        RouteTarget target = configService.target(purpose);
        if (target == null || target.provider() == null || target.provider().isBlank()) {
            throw BizException.of(500, "SYS_PROVIDER_ERROR", "入口未配置模型路由: " + purpose.getCode());
        }
        if (channels.channel(target.provider()) == null) {
            throw BizException.of(500, "SYS_PROVIDER_ERROR",
                    "路由指向未配置的渠道: " + target.provider());
        }
        return target;
    }

    /**
     * 路由目标 + 请求级覆盖合并为 LlmCallSpec：请求值优先。
     * 路由不再管理温度与输出预算——请求未覆盖时直接传 null（不设参数 = 用模型默认值）；
     * 超时：请求级显式值 > 全局 zhiji.llm.timeout-seconds（spec.of 内处理 null 回落）。
     */
    private LlmCallSpec requireSpec(LlmRequest request) {
        RouteTarget target = requireTarget(request.purpose());
        return LlmCallSpec.of(
                target.provider(),
                request.model() != null && !request.model().isBlank() ? request.model() : target.model(),
                request.temperature(),
                request.maxTokens(),
                request.thinking() != null ? request.thinking() : target.thinking(),
                request.timeoutSeconds() != null && request.timeoutSeconds() > 0
                        ? request.timeoutSeconds()
                        : null);
    }

    private <T> T parseJson(String raw, Class<T> type) {
        return jsonMapper.readValue(stripCodeFence(raw), type);
    }

    /** 剥离模型常包的 ```json ... ``` 围栏，避免因围栏导致解析失败。 */
    private String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int start = text.indexOf('\n');
        int end = text.lastIndexOf("```");
        if (start < 0 || end <= start) {
            return text;
        }
        return text.substring(start + 1, end).trim();
    }

    /** 模型输出打点用：只留一小段原文，便于排查 prompt 漂移而非淹没日志。 */
    private String preview(String raw) {
        if (raw == null) {
            return "<null>";
        }
        String text = raw.strip();
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private BizException providerError(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        return BizException.of(502, "SYS_PROVIDER_ERROR", "模型服务调用失败：" + e.getMessage());
    }
}
