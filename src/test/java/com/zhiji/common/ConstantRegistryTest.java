package com.zhiji.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 常量组下发：全量与单组查询，未知组 404。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConstantRegistryTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void allGroupsExposed() throws Exception {
        mvc.perform(get("/api/constants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staff_role").isNotEmpty())
                .andExpect(jsonPath("$.account_status").isNotEmpty());
    }

    @Test
    void singleGroupAndUnknownGroup() throws Exception {
        mvc.perform(get("/api/constants/staff_role"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("admin"))
                .andExpect(jsonPath("$[0].name").value("管理员"));
        mvc.perform(get("/api/constants/not_exists"))
                .andExpect(status().isNotFound());
    }
}
