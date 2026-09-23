package com.zhiji.llm.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmRouteConfig.RouteTarget;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 调用编排（LlmClient 的实现）：路由解析 → 限额守卫 → 网络调用 → JSON 校验与重试 → 计数落库。
 * 业务模块只依赖 LlmClient，不感知 provider、模型名与重试细节。
 */
@Service
public class LlmGatewayService implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayService.class);

    private final LlmConfigService configService;
    private final LlmProperties properties;
    private final LlmRateGuard rateGuard;
    private final LlmUsageService usageService;
    private final LlmTransport transport;
    private final JsonMapper jsonMapper = new JsonMapper();

    public LlmGatewayService(LlmConfigService configService, LlmProperties properties, LlmRateGuard rateGuard,
            LlmUsageService usageService, LlmTransport transport) {
        this.configService = configService;
        this.properties = properties;
        this.rateGuard = rateGuard;
        this.usageService = usageService;
        this.transport = transport;
    }

    @Override
    public String complete(LlmRequest request) {
        RouteTarget target = requireTarget(request.purpose());
        long profileId = request.profileKey();
        rateGuard.check(request.purpose(), profileId);
        try {
            String text = transport.call(target.provider(), target.model(),
                    request.temperature() != null ? request.temperature() : target.temperature(),
                    request.maxTokens() != null ? request.maxTokens() : target.maxTokens(),
                    request.systemPrompt(), request.userPrompt());
            usageService.recordCall(request.purpose().getCode(), profileId);
            return text;
        } catch (RuntimeException e) {
            usageService.recordFail(request.purpose().getCode(), profileId);
            throw providerError(e);
        }
    }

    @Override
    public <T> T completeJson(LlmRequest request, Class<T> type) {
        RouteTarget target = requireTarget(request.purpose());
        long profileId = request.profileKey();
        rateGuard.check(request.purpose(), profileId);

        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw = null;
            try {
                raw = transport.call(target.provider(), target.model(),
                        request.temperature() != null ? request.temperature() : target.temperature(),
                        request.maxTokens() != null ? request.maxTokens() : target.maxTokens(),
                        request.systemPrompt(), request.userPrompt());
                usageService.recordCall(request.purpose().getCode(), profileId);
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
        usageService.recordFail(request.purpose().getCode(), profileId);
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
            usageService.recordCall(purpose.getCode(), profileKey);
            return vectors;
        } catch (RuntimeException e) {
            usageService.recordFail(purpose.getCode(), profileKey);
            throw providerError(e);
        }
    }

    private RouteTarget requireTarget(LlmPurpose purpose) {
        RouteTarget target = configService.target(purpose);
        if (target == null || target.provider() == null || target.provider().isBlank()) {
            throw BizException.of(500, "SYS_PROVIDER_ERROR", "入口未配置模型路由: " + purpose.getCode());
        }
        if (!properties.getProviders().containsKey(target.provider())) {
            throw BizException.of(500, "SYS_PROVIDER_ERROR",
                    "路由指向未配置的 provider: " + target.provider());
        }
        return target;
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
