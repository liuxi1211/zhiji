package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.llm.api.LlmPurpose;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 通用配置接口与 LLM 运维接口：/api/admin/configs 仅 admin 可访问（editor 403）、
 * 配置域读写落库刷新快照、密钥脱敏下发、连通性自检按目标去重、
 * 用量按账号（C 端）/入口（B 端）聚合、路由域非法输入被挡在落库前。
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

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        stub.respond("ok");
    }

    @Test
    void getConfigsRequiresStaffSession() throws Exception {
        mvc.perform(get("/api/admin/configs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editorForbiddenOnConfigs() throws Exception {
        String editorToken = createAndLoginEditor();
        mvc.perform(get("/api/admin/configs").header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void adminCanReadAndUpdateLimits() throws Exception {
        String adminToken = login("admin", "admin123");
        // 域按 order 排序：llm.channel(0) / llm.route(1) / llm.limits(2) / llm.prompt(3)
        mvc.perform(get("/api/admin/configs").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("llm.channel"))
                // 渠道密钥脱敏下发：掩码 **** 开头，真实密钥不出后端
                .andExpect(jsonPath("$[0].value.channels.ark.apiKey").value(org.hamcrest.Matchers.startsWith("****")))
                .andExpect(jsonPath("$[2].value.customerDaily").isNumber());

        mvc.perform(put("/api/admin/configs/llm.limits").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerDaily\":150,\"customerWeekly\":700,\"customerMonthly\":1800,"
                                + "\"staffDaily\":250,\"staffWeekly\":1000,\"staffMonthly\":3500,"
                                + "\"siteDailyTotal\":900}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.customerDaily").value(150));

        mvc.perform(get("/api/admin/configs").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].value.customerDaily").value(150));
    }

    @Test
    void adminCanUpdateRoutes() throws Exception {
        String adminToken = login("admin", "admin123");
        // 模型名必须取自渠道的 models 清单（渠道卡事实源），用清单内的 deepseek-v4.1-flash
        mvc.perform(put("/api/admin/configs/llm.route").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purposes\":{\"judge_practice\":{\"provider\":\"ark\","
                                + "\"model\":\"deepseek-v4.1-flash\",\"embedding\":false}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.purposes.judge_practice.model").value("deepseek-v4.1-flash"));
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
        mvc.perform(put("/api/admin/configs/llm.route").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purposes\":{\"judge_unknown\":{\"provider\":\"ark\","
                                + "\"model\":\"doubao-seed-2-0-lite-260428\",\"embedding\":false}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("judge_unknown")));
    }

    @Test
    void routesRejectUnconfiguredProviderAndMissingModel() throws Exception {
        String adminToken = login("admin", "admin123");
        mvc.perform(put("/api/admin/configs/llm.route").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purposes\":{\"judge_practice\":{\"provider\":\"openai\","
                                + "\"model\":\"gpt-4o\",\"embedding\":false}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("渠道")));
        mvc.perform(put("/api/admin/configs/llm.route").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purposes\":{\"judge_practice\":{\"provider\":\"ark\","
                                + "\"model\":\"  \",\"embedding\":false}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("模型名")));
        configService.saveRouteConfig(configService.defaultRouteConfig());
    }

    @Test
    void routesRejectEmptyPayload() throws Exception {
        String adminToken = login("admin", "admin123");
        mvc.perform(put("/api/admin/configs/llm.route").header("Authorization", "Bearer " + adminToken)
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
                .andExpect(jsonPath("$.customers").isArray())
                .andExpect(jsonPath("$.staff").isArray())
                .andExpect(jsonPath("$.customerDailyLimit").isNumber())
                .andExpect(jsonPath("$.staffDaily").isNumber())
                .andExpect(jsonPath("$.todayTotal").isNumber())
                .andExpect(jsonPath("$.siteDailyTotal").isNumber());
    }

    @Test
    void usageViewAggregatesCustomerByAccount() throws Exception {
        usageService.deleteByDate(usageService.today());
        jdbcTemplate.update("INSERT INTO sys_customer (username, password_hash) VALUES (?, 'x')",
                "usageview-" + System.nanoTime());
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_customer WHERE username LIKE 'usageview-%' ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO prf_profile (customer_id, name) VALUES (?, '用量测试档案')", customerId);
        long profileId = jdbcTemplate.queryForObject(
                "SELECT id FROM prf_profile WHERE customer_id = ? ORDER BY id DESC LIMIT 1", Long.class, customerId);
        try {
            // 同账号三入口合计进同一个账号的日窗口
            for (int i = 0; i < 2; i++) {
                usageService.recordCall(LlmPurpose.JUDGE_PRACTICE, profileId);
            }
            usageService.recordCall(LlmPurpose.EXAM_SUMMARY, profileId);
            usageService.recordCall(LlmPurpose.SOURCE_REWRITE, 0L);
            String adminToken = login("admin", "admin123");
            mvc.perform(get("/api/admin/settings/usage").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customers[0].daily").value(3))
                    .andExpect(jsonPath("$.customers[0].monthly").value(3))
                    .andExpect(jsonPath("$.customers[0].username").value(
                            org.hamcrest.Matchers.startsWith("usageview-")))
                    .andExpect(jsonPath("$.staff[?(@.purpose == 'source_rewrite')].today").value(1));
        } finally {
            usageService.deleteByDate(usageService.today());
            jdbcTemplate.update("DELETE FROM prf_profile WHERE id = ?", profileId);
            jdbcTemplate.update("DELETE FROM sys_customer WHERE id = ?", customerId);
        }
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
