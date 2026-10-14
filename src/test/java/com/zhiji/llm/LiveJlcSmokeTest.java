package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实 provider 冒烟（公司代理渠道 jlc，anthropic messages 协议）：
 * 验证 路由 → 限额守卫 → anthropic 出网 → JSON 校验 → 计数 的整条链路。
 * 仅在提供 ZHIJI_LLM_JLC_KEY 时运行——日常 mvn test 无 key 即自动跳过，不触网、不消耗额度。
 *
 * 运行方式：$env:ZHIJI_LLM_JLC_KEY="<key>"; mvn test -Dtest=LiveJlcSmokeTest
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ZHIJI_LLM_JLC_KEY", matches = ".+")
class LiveJlcSmokeTest {

    @DynamicPropertySource
    static void jlcCredentials(DynamicPropertyRegistry registry) {
        registry.add("zhiji.llm.providers.jlc.base-url", () -> "https://claude.jlcops.com/api");
        registry.add("zhiji.llm.providers.jlc.api-key", () -> System.getenv("ZHIJI_LLM_JLC_KEY"));
    }

    @Autowired
    LlmClient llmClient;

    @Autowired
    LlmConfigService configService;

    @Autowired
    LlmUsageService usageService;

    record Verdict(String verdict, String reason) {
    }

    @Test
    void completeJsonReturnsParsedObjectAndCountsUsage() {
        configService.saveRouteConfig(configService.defaultRouteConfig());
        long before = usageService.todayTotal();
        Verdict verdict = llmClient.completeJson(LlmRequest.user(LlmPurpose.JUDGE_PRACTICE, 999L,
                        "用户回答：Redis 的增量同步靠 repl_backlog 环形缓冲，主从断连重连后按 offset 续传。")
                .systemPrompt("你是面试判定器，只输出 JSON，不要任何解释：{\"verdict\":\"hit 或 shallow\",\"reason\":\"一句话\"}")
                .temperature(0.0)
                .maxTokens(256)
                .build(),
                Verdict.class);
        assertThat(verdict).isNotNull();
        assertThat(verdict.verdict()).isNotBlank();
        assertThat(usageService.todayTotal()).isEqualTo(before + 1);
    }

    @Test
    void streamCompletesWithDeltas() {
        configService.saveRouteConfig(configService.defaultRouteConfig());
        StringBuilder deltas = new StringBuilder();
        String full = llmClient.streamComplete(LlmRequest.user(LlmPurpose.SOURCE_REWRITE, null,
                "用一句话介绍 Redis 的 repl_backlog。").maxTokens(256).build(), deltas::append);
        assertThat(full).isNotBlank();
        assertThat(deltas).isNotEmpty();
    }
}
