package com.zhiji.llm.service;

import com.zhiji.llm.domain.LlmChannelConfig.Channel;
import com.zhiji.llm.domain.LlmRouteConfig;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * LLM 启动诊断（ConfigRegistry.load 之后执行）：回显运行参数摘要与渠道密钥就绪情况——
 * 密钥在渠道库（llm.channel）里配了没配，启动日志即可判断（只报有无，不报值）。
 * 渠道/提示词的种子写入与老库修复已收拢进 LlmChannelSection / LlmPromptSection（defaults/afterLoad）。
 */
@Component
public class LlmConfigInitializer implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigInitializer.class);

    private final LlmConfigService configService;
    private final LlmChannelService channels;

    public LlmConfigInitializer(LlmConfigService configService, LlmChannelService channels) {
        this.configService = configService;
        this.channels = channels;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public void run(ApplicationArguments args) {
        LlmRouteConfig route = configService.routeConfig();
        var limits = configService.limits();
        log.info("LLM 运行参数已加载：路由 {} 个入口，限额 C端 日/周/月={}/{}/{}，B端 日/周/月={}/{}/{}，全站日熔断={}",
                route == null || route.purposes() == null ? 0 : route.purposes().size(),
                limits.customerDaily(), limits.customerWeekly(), limits.customerMonthly(),
                limits.staffDaily(), limits.staffWeekly(), limits.staffMonthly(),
                limits.siteDailyTotal());
        log.info("LLM 渠道密钥就绪情况：{}（来源：LLM 管理页渠道库，未配置的渠道调不通）",
                channelKeysStatus());
    }

    private String channelKeysStatus() {
        Map<String, Channel> channelMap = channels.all().channels();
        if (channelMap.isEmpty()) {
            return "无渠道";
        }
        return channelMap.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + (entry.getValue().configured() ? "已配置" : "未配置"))
                .collect(Collectors.joining(", "));
    }
}
