package com.zhiji.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 后台人员管理闭环：admin 自举登录 → 创建 editor → editor 权限受限 →
 * 停用后即时失效（D18 硬验收）。
 */
@SpringBootTest
@ActiveProfiles("test")
class StaffAuthTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void adminBootstrapCreateEditorAndDisable() throws Exception {
        String adminToken = loginStaff("admin", "admin123");

        String editorName = "ed" + System.nanoTime();
        MvcResult created = mvc.perform(post("/api/admin/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminToken)
                        .content(body(Map.of("username", editorName, "password", "Editor123", "role", "editor"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("editor"))
                .andReturn();
        long editorId = Long.parseLong(field(created.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));

        String editorToken = loginStaff(editorName, "Editor123");
        mvc.perform(get("/api/auth/staff/me").header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("editor"));

        mvc.perform(get("/api/admin/accounts").header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mvc.perform(post("/api/admin/accounts/" + editorId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("status", "disabled"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"));

        mvc.perform(get("/api/auth/staff/me").header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void adminCannotDisableSelf() throws Exception {
        String adminToken = loginStaff("admin", "admin123");
        String me = mvc.perform(get("/api/auth/staff/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long adminId = Long.parseLong(field(me, "id"));
        mvc.perform(post("/api/admin/accounts/" + adminId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("status", "disabled"))))
                .andExpect(status().isBadRequest());
    }

    private String loginStaff(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
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
