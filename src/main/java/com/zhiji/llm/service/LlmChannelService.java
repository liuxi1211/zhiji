package com.zhiji.llm.service;

import com.zhiji.common.config.ConfigRegistry;
import com.zhiji.llm.config.LlmChannelSection;
import com.zhiji.llm.config.LlmProperties;
import com.zhiji.llm.domain.LlmChannelConfig;
import com.zhiji.llm.domain.LlmChannelConfig.Channel;
import org.springframework.stereotype.Service;

/**
 * 模型渠道门面：唯一读 llm.channel 快照的地方，传输层 / 路由校验 / 健康自检统一取数。
 * 快照缺失（测试上下文未跑 ApplicationRunner）时回落 yml 种子，语义与历史 LlmProperties 一致。
 */
@Service
public class LlmChannelService {

    private final ConfigRegistry registry;
    private final LlmProperties seedProperties;

    public LlmChannelService(ConfigRegistry registry, LlmProperties seedProperties) {
        this.registry = registry;
        this.seedProperties = seedProperties;
    }

    public LlmChannelConfig all() {
        LlmChannelConfig config = registry.get(LlmChannelSection.KEY);
        return config != null ? config : LlmChannelConfig.fromSeed(seedProperties.getProviders());
    }

    public Channel channel(String name) {
        return all().channel(name);
    }

    public Channel require(String name) {
        Channel channel = channel(name);
        if (channel == null) {
            throw new IllegalArgumentException("未配置的 LLM 渠道: " + name);
        }
        return channel;
    }
}
