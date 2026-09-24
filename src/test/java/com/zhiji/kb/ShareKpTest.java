package com.zhiji.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 知识卡分享公开接口（知识库PRD 7.5 / 开发计划 T5 验收）：
 * 无 token 可达、只输出 active、公开视图不含 levelReq / rubric、停用层不下发、404 不泄露存在性。
 */
@SpringBootTest
@ActiveProfiles("test")
class ShareKpTest {

    private static final String BEARER = "Authorization";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    JsonMapper om = new JsonMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void shareReturnsActiveKpPublicViewWithoutToken() throws Exception {
        long kpId = seedKp("active", "分享样板知识点");

        mvc.perform(get("/api/share/kp/" + kpId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(kpId))
                .andExpect(jsonPath("$.difficulty").value(4));

        JsonNode node = getJson("/api/share/kp/" + kpId);
        assertTrue(node.has("followups") && node.get("followups").size() >= 1, "追问链应下发");
        assertEquals("TCP连接为什么是三次", node.get("followups").get(0).get("text").asText(),
                "追问层文本应原样下发");
        assertTrue(node.has("samples") && node.get("samples").has("pass"), "三档样例应下发");
        assertFalse(node.has("levelReq"), "目标刻度依赖个人职级，公开视图不得含 levelReq");
        assertFalse(node.has("rubric"), "评分要点是判定尺子，公开视图不得含 rubric");
        assertTrue(node.get("domainName").asText().startsWith("测试分享域"));
        assertTrue(node.get("clusterName").asText().startsWith("测试分享簇"));
        assertTrue(node.get("zeroShotMd").asText().contains("从零讲正文"), "从零讲应下发");
    }

    @Test
    void shareFiltersDisabledLayers() throws Exception {
        long kpId = seedKp("active", "停用层过滤样板");

        JsonNode node = getJson("/api/share/kp/" + kpId);
        for (JsonNode layer : node.get("followups")) {
            assertFalse("disabled".equals(layer.get("layerKey").asText()),
                    "停用层（status=disabled）不应出现在公开视图");
        }
        assertEquals(1, node.get("followups").size());
    }

    @Test
    void shareRejectsNonActiveWithNotFound() throws Exception {
        for (String status : List.of("draft", "reviewed", "retired")) {
            long kpId = seedKp(status, "非上线" + status);
            mvc.perform(get("/api/share/kp/" + kpId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
        mvc.perform(get("/api/share/kp/999999999"))
                .andExpect(status().isNotFound());
    }

    private long seedKp(String status, String title) {
        String suffix = String.valueOf(System.nanoTime());
        jdbc.update("INSERT INTO skill_domain (name, description, sort_order, status) VALUES (?, NULL, 0, 'active')",
                "测试分享域" + suffix);
        long domainId = jdbc.queryForObject("SELECT id FROM skill_domain WHERE name = ?",
                Long.class, "测试分享域" + suffix);
        jdbc.update("INSERT INTO kp_cluster (domain_id, name, zero_shot_md, status) VALUES (?, ?, ?, 'active')",
                domainId, "测试分享簇" + suffix, "从零讲正文" + suffix);
        long clusterId = jdbc.queryForObject("SELECT id FROM kp_cluster WHERE name = ?",
                Long.class, "测试分享簇" + suffix);

        String followups = "[{\"layerKey\":\"L1\",\"dir\":\"深入\",\"text\":\"TCP连接为什么是三次\","
                + "\"expect\":\"历史连接\",\"stopCondition\":null,\"status\":\"active\"},"
                + "{\"layerKey\":\"L2\",\"dir\":\"深入\",\"text\":\"停用的追问\","
                + "\"expect\":null,\"stopCondition\":null,\"status\":\"disabled\"}]";
        String samples = "{\"pass\":\"能背出三步\",\"good\":\"带出状态机\",\"excellent\":\"结合抓包\"}";
        jdbc.update("INSERT INTO kp_main (cluster_id, sub_type, title, variants, followups, rubric, samples, "
                        + "level_req, baseline_levels, difficulty, status) "
                        + "VALUES (?, 'default', ?, '[\"三次握手\"]', ?, '[{\"layerKey\":\"main\","
                        + "\"points\":[{\"pointId\":\"p00000001\",\"level\":\"basic\",\"text\":\"三步\"}]}]', "
                        + "?, '{\"junior\":{\"required\":true,\"stopLayer\":\"main\"}}', 'junior', 4, ?)",
                clusterId, title + suffix, followups, samples, status);
        return jdbc.queryForObject("SELECT id FROM kp_main WHERE title = ?", Long.class, title + suffix);
    }

    private JsonNode getJson(String uri) throws Exception {
        MvcResult result = mvc.perform(get(uri))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
