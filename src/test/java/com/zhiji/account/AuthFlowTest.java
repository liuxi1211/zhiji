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
 * 客户注册 / 登录 / me 全流程，以及错误码口径（重复 409、口令错误 401）。
 * 测试库共享（临时 SQLite），用户名用 nanoTime 保证唯一。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthFlowTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void registerLoginMeFlow() throws Exception {
        String username = "cu" + System.nanoTime();
        mvc.perform(post("/api/auth/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value(username));

        MvcResult login = mvc.perform(post("/api/auth/customer/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk())
                .andReturn();
        String token = field(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");

        mvc.perform(get("/api/auth/customer/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void duplicateUsernameIs409() throws Exception {
        String username = "du" + System.nanoTime();
        mvc.perform(post("/api/auth/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_USERNAME_TAKEN"));
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        String username = "wp" + System.nanoTime();
        mvc.perform(post("/api/auth/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/customer/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Wrong123",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_FAILED"));
    }

    @Test
    void anonymousCustomerApiIs401() throws Exception {
        mvc.perform(get("/api/auth/customer/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
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
