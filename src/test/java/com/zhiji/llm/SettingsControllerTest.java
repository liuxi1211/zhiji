package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 系统配置接口：仅 admin 可访问（editor 403）、路由/限额读写落库刷新快照、
 * 连通性自检按目标去重、用量对照按单档案峰值比较、路由非法输入被挡在落库前。
 */
@SpringBootTest
@ActiveProfiles("test")
class SettingsControllerTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubLlmTransport stubLlmTransport() {
            return new StubLlmTransport();
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    StubLlmTransport stub;

    @Autowired
    LlmConfigService configService;

    @Autowired
    LlmUsageService usageService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        stub.respond("ok");
    }

    @Test
    void getSettingsRequiresStaffSession() throws Exception {
        mvc.perform(get("/api/admin/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editorForbiddenOnSettings() throws Exception {
        String editorToken = createAndLoginEditor();
        mvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void adminCanReadAndUpdateLimits() throws Exception {
        String adminToken = login("admin", "admin123");
        mvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.limits.judgeDaily").isNumber());

        mvc.perform(put("/api/admin/settings/limits").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"judgeDaily\":150,\"variantRewriteDaily\":10,"
                                + "\"perProfileDailyTotal\":250,\"siteDailyTotal\":900}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limits.judgeDaily").value(150));

        mvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limits.judgeDaily").value(150));
    }

    @Test
    void adminCanUpdateRoutes() throws Exception {
        String adminToken = login("admin", "admin123");
        // 模型名必须取自 provider 的 models 清单（yml 事实源），用清单内的 deepseek-v4.1-flash
        mvc.perform(put("/api/admin/settings/routes").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routes\":{\"judge_practice\":{\"provider\":\"ark\","
                                + "\"model\":\"deepseek-v4.1-flash\",\"temperature\":0.0,"
                                + "\"maxTokens\":1024,\"embedding\":false}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes.judge_practice.model").value("deepseek-v4.1-flash"));
        configService.saveRouteConfig(configService.defaultRouteConfig());
    }

    @Test
    void connectivityCheckProbesEachUniqueTargetOnce() throws Exception {
        String adminToken = login("admin", "admin123");
        // 默认路由 JUDGE/QUALITY 同模型（provider default-model），去重后唯一目标 = chat + embedding = 2
        configService.saveRouteConfig(configService.defaultRouteConfig());
        mvc.perform(post("/api/admin/settings/connectivity-check")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allOk").value(true))
                .andExpect(jsonPath("$.items.length()").value(2));
        assertThat(stub.callCount()).isEqualTo(2);
    }

    @Test
    void routesRejectUnknownPurpose() throws Exception {
        String adminToken = login("admin", "admin123");
        mvc.perform(put("/api/admin/settings/routes").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routes\":{\"judge_unknown\":{\"provider\":\"ark\","
                                + "\"model\":\"doubao-seed-2-0-lite-260428\",\"temperature\":0.0,"
                                + "\"maxTokens\":1024,\"embedding\":false}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("judge_unknown")));
    }

    @Test
    void routesRejectUnconfiguredProviderAndMissingModel() throws Exception {
        String adminToken = login("admin", "admin123");
        mvc.perform(put("/api/admin/settings/routes").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routes\":{\"judge_practice\":{\"provider\":\"openai\","
                                + "\"model\":\"gpt-4o\",\"temperature\":0.0,"
                                + "\"maxTokens\":1024,\"embedding\":false}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("provider")));
        mvc.perform(put("/api/admin/settings/routes").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routes\":{\"judge_practice\":{\"provider\":\"ark\","
                                + "\"model\":\"  \",\"temperature\":0.0,"
                                + "\"maxTokens\":1024,\"embedding\":false}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("模型名")));
        configService.saveRouteConfig(configService.defaultRouteConfig());
    }

    @Test
    void routesRejectEmptyPayload() throws Exception {
        String adminToken = login("admin", "admin123");
        mvc.perform(put("/api/admin/settings/routes").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION"));
    }

    @Test
    void usageViewExposesLimitsAndTotals() throws Exception {
        String adminToken = login("admin", "admin123");
        mvc.perform(get("/api/admin/settings/usage").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes").isArray())
                .andExpect(jsonPath("$.siteDailyTotal").isNumber())
                .andExpect(jsonPath("$.perProfileDailyTotal").isNumber())
                .andExpect(jsonPath("$.peakProfileTotal").isNumber());
    }

    @Test
    void usageViewCountsJudgeAgainstPeakProfileNotSiteTotal() throws Exception {
        usageService.deleteByDate(usageService.today());
        for (int i = 0; i < 3; i++) {
            usageService.recordCall(LlmPurpose.JUDGE_PRACTICE.getCode(), 101L);
        }
        usageService.recordCall(LlmPurpose.JUDGE_PRACTICE.getCode(), 102L);
        configService.saveLimits(new LlmLimits(3, 20, 300, 1000));
        String adminToken = login("admin", "admin123");
        mvc.perform(get("/api/admin/settings/usage").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].callCount").value(4))
                .andExpect(jsonPath("$.purposes[0].peakProfileCount").value(3))
                .andExpect(jsonPath("$.purposes[0].soft").value(true))
                .andExpect(jsonPath("$.purposes[0].exceeded").value(true));
        configService.saveLimits(LlmLimits.defaults());
        usageService.deleteByDate(usageService.today());
    }

    private String createAndLoginEditor() throws Exception {
        String adminToken = login("admin", "admin123");
        String editorName = "sed" + System.nanoTime();
        mvc.perform(post("/api/admin/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body(Map.of("username", editorName, "password", "Editor123", "role", "editor"))))
                .andExpect(status().isOk());
        return login(editorName, "Editor123");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
    }

    private String body(Map<String, String> map) throws Exception {
        var om = new tools.jackson.databind.json.JsonMapper();
        return om.writeValueAsString(map);
    }

    private String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name);
        }
        return matcher.group(1);
    }
}
