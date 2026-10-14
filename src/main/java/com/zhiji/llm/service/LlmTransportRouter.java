package com.zhiji.llm.service;

import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmChannelConfig.Channel;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 传输层分发：按渠道的 protocol 配置把调用转给对应协议实现。
 * openai → SpringAiTransport（Spring AI OpenAI 兼容适配器），
 * anthropic → AnthropicTransport（公司代理渠道 messages 协议）。
 * 保留 LlmTransport 接口语义，业务编排（LlmGatewayService）无感知。
 * 渠道事实源为 llm.channel（LlmChannelService），渠道卡保存后分发目标随协议即时切换。
 */
@Component
public class LlmTransportRouter implements LlmTransport {

    private final LlmChannelService channels;
    private final LlmProperties properties;
    private final SpringAiTransport springAiTransport;
    private final AnthropicTransport anthropicTransport;

    public LlmTransportRouter(LlmChannelService channels, LlmProperties properties) {
        this.channels = channels;
        this.properties = properties;
        this.springAiTransport = new SpringAiTransport(channels, properties);
        this.anthropicTransport = new AnthropicTransport(channels, properties);
    }

    private LlmTransport route(String provider) {
        Channel target = channels.require(provider);
        return "anthropic".equalsIgnoreCase(target.protocolOrDefault()) ? anthropicTransport : springAiTransport;
    }

    @Override
    public String call(LlmCallSpec spec, String systemPrompt, String userPrompt) {
        return route(spec.provider()).call(spec, systemPrompt, userPrompt);
    }

    @Override
    public String stream(LlmCallSpec spec, String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        return route(spec.provider()).stream(spec, systemPrompt, userPrompt, onDelta);
    }

    @Override
    public List<float[]> embed(String provider, String model, List<String> texts) {
        return route(provider).embed(provider, model, texts);
    }
}
