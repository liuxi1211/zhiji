package com.zhiji.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 双树会话隔离（D18 硬验收）：客户 token 打不进 /admin 接口，员工 token 打不进客户接口。
 */
@SpringBootTest
@ActiveProfiles("test")
class InterceptorScopeTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void crossTreeTokensAreRejected() throws Exception {
        String username = "cx" + System.nanoTime();
        mvc.perform(post("/api/auth/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk());
        MvcResult customerLogin = mvc.perform(post("/api/auth/customer/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk())
                .andReturn();
        String customerToken = field(customerLogin.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");

        MvcResult staffLogin = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        String staffToken = field(staffLogin.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");

        mvc.perform(get("/api/admin/accounts").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/customer/me").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isUnauthorized());
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
