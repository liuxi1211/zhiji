package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.domain.LlmRouteConfig;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * 调用编排：路由命中、JSON 校验重试、失败降级、计数落库。
 * 用 StubLlmTransport 完全替代网络，验证编排逻辑而不触网。
 */
@SpringBootTest
@ActiveProfiles("test")
class LlmGatewayTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubLlmTransport stubLlmTransport() {
            return new StubLlmTransport();
        }
    }

    @Autowired
    LlmClient llmClient;

    @Autowired
    StubLlmTransport stub;

    @Autowired
    LlmConfigService configService;

    @Autowired
    LlmUsageService usageService;

    record Verdict(String verdict, int score) {
    }

    @BeforeEach
    void reset() {
        stub.reset();
        configService.saveLimits(LlmLimits.defaults());
        configService.saveRouteConfig(configService.defaultRouteConfig());
        usageService.deleteByDate(usageService.today());
    }

    @Test
    void routesByPurposeAndReturnsText() {
        stub.respond("hello");
        String result = llmClient.complete(LlmRequest.user(LlmPurpose.EXAM_SUMMARY, null, "说点什么").build());
        assertThat(result).isEqualTo("hello");
        assertThat(usageService.todayTotal()).isEqualTo(1);
    }

    @Test
    void parsesValidJson() {
        stub.respond("{\"verdict\":\"hit\",\"score\":80}");
        Verdict verdict = llmClient.completeJson(
                LlmRequest.user(LlmPurpose.JUDGE_PRACTICE, 1L, "判断").build(), Verdict.class);
        assertThat(verdict.verdict()).isEqualTo("hit");
        assertThat(verdict.score()).isEqualTo(80);
    }

    @Test
    void stripsMarkdownFence() {
        stub.respond("```json\n{\"verdict\":\"hit\",\"score\":60}\n```");
        Verdict verdict = llmClient.completeJson(
                LlmRequest.user(LlmPurpose.JUDGE_PRACTICE, 1L, "判断").build(), Verdict.class);
        assertThat(verdict.score()).isEqualTo(60);
    }

    @Test
    void retriesOnceOnInvalidJsonThenSucceeds() {
        stub.script("not-json", "{\"verdict\":\"shallow\",\"score\":30}");
        Verdict verdict = llmClient.completeJson(
                LlmRequest.user(LlmPurpose.JUDGE_PRACTICE, 1L, "判断").build(), Verdict.class);
        assertThat(verdict.verdict()).isEqualTo("shallow");
        assertThat(stub.callCount()).isEqualTo(2);
    }

    @Test
    void failsWithProviderErrorAfterTwoInvalidJson() {
        stub.respond("still-not-json");
        assertThatThrownBy(() -> llmClient.completeJson(
                LlmRequest.user(LlmPurpose.JUDGE_PRACTICE, 1L, "判断").build(), Verdict.class))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("SYS_PROVIDER_ERROR");
        assertThat(stub.callCount()).isEqualTo(2);
    }

    @Test
    void transportFailureBecomesProviderErrorAndCountsFail() {
        stub.fail(new IllegalStateException("network down"));
        assertThatThrownBy(() -> llmClient.complete(LlmRequest.user(LlmPurpose.EXAM_SUMMARY, null, "x").build()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("SYS_PROVIDER_ERROR");
        assertThat(configService.routeConfig().purposes()).containsKey(LlmPurpose.EXAM_SUMMARY.getCode());
        assertThat(usageService.todayRows()).anyMatch(row ->
                LlmPurpose.EXAM_SUMMARY.getCode().equals(row.getPurpose()) && row.getFailCount() == 1);
    }

    @Test
    void unmappedPurposeFailsFast() {
        configService.saveRouteConfig(new LlmRouteConfig(Map.of()));
        assertThatThrownBy(() -> llmClient.complete(LlmRequest.user(LlmPurpose.EXAM_SUMMARY, null, "x").build()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("SYS_PROVIDER_ERROR");
        configService.saveRouteConfig(configService.defaultRouteConfig());
    }
}
