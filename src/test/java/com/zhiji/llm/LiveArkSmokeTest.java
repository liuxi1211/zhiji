package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实 provider 冒烟（火山方舟 coding plan）：验证 路由 → 限额守卫 → Spring AI 出网 → JSON 校验 → 计数 的整条链路。
 * 仅在提供 ZHIJI_LLM_ARK_KEY 时运行——CI 与日常 mvn test 无 key 即自动跳过，不会触网、不消耗额度。
 *
 * 运行方式：$env:ZHIJI_LLM_ARK_KEY="<key>"; mvn test -Dtest=LiveArkSmokeTest
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ZHIJI_LLM_ARK_KEY", matches = ".+")
class LiveArkSmokeTest {

    @DynamicPropertySource
    static void arkCredentials(DynamicPropertyRegistry registry) {
        registry.add("zhiji.llm.providers.ark.base-url", () -> "https://ark.cn-beijing.volces.com/api/coding/v3");
        registry.add("zhiji.llm.providers.ark.api-key", () -> System.getenv("ZHIJI_LLM_ARK_KEY"));
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
                .maxTokens(128)
                .build(),
                Verdict.class);
        assertThat(verdict).isNotNull();
        assertThat(verdict.verdict()).isNotBlank();
        assertThat(usageService.todayTotal()).isEqualTo(before + 1);
    }

    @Test
    void embeddingReturnsVectors() {
        configService.saveRouteConfig(configService.defaultRouteConfig());
        List<float[]> vectors = llmClient.embedAll(LlmPurpose.EMBEDDING, null, List.of("Redis 近实时同步"));
        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0).length).isGreaterThan(1);
    }
}