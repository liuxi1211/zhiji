package com.zhiji.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 * 簇管理 B 端接口全流程：建簇的域校验、列表/详情投影（domainName / kpCount / kpList）、
 * 状态机推进与复核时间落库、合并整体迁移知识点、鉴权隔离。
 * 测试库共享（临时 SQLite），名称用 nanoTime 保证唯一。
 */
@SpringBootTest
@ActiveProfiles("test")
class ClusterTest {

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
    void createOnActiveDomainOkButDisabledAndMissingDomainRejected() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain("域A" + suffix, "active");
        long clusterId = createCluster(token, domainId, "簇A" + suffix);

        JsonNode detail = getJson(token, "/api/admin/clusters/" + clusterId);
        assertEquals("簇A" + suffix, detail.get("name").asText());
        assertEquals("draft", detail.get("status").asText());
        assertTrue(detail.get("mergedInto").isNull());

        long disabledId = insertDomain("域B" + suffix, "disabled");
        mvc.perform(post("/api/admin/clusters").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("domainId", disabledId, "name", "簇X" + suffix))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_DOMAIN_INVALID"));

        mvc.perform(post("/api/admin/clusters").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("domainId", 999999999, "name", "簇Y" + suffix))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_DOMAIN_INVALID"));
    }

    @Test
    void listCarriesDomainNameAndKpCountAndDetailCarriesKpList() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain("域C" + suffix, "active");
        long clusterId = createCluster(token, domainId, "簇C" + suffix);
        insertKp(clusterId, "题一" + suffix, "draft");
        insertKp(clusterId, "题二" + suffix, "retired");

        JsonNode row = findByName(getJson(token, "/api/admin/clusters").get("list"), "簇C" + suffix);
        assertNotNull(row);
        assertEquals("域C" + suffix, row.get("domainName").asText());
        assertEquals(1, row.get("kpCount").asLong());
        assertEquals("draft", row.get("status").asText());
        assertTrue(row.has("lastReviewedAt"));
        assertTrue(row.has("reviewDueAt"));
        assertTrue(row.has("createdAt"));
        assertTrue(row.has("updatedAt"));

        JsonNode detail = getJson(token, "/api/admin/clusters/" + clusterId);
        assertEquals(2, detail.get("kpList").size());
        assertEquals("题一" + suffix, detail.get("kpList").get(0).get("title").asText());
        assertEquals(1, detail.get("kpList").get(0).get("difficulty").asInt());
    }

    @Test
    void statusProgressWritesReviewTimesAndIllegalMovesRejected() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain("域D" + suffix, "active");
        long clusterId = createCluster(token, domainId, "簇D" + suffix);

        mvc.perform(post("/api/admin/clusters/" + clusterId + "/status").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "reviewed"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/clusters/" + clusterId + "/status").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "active"))))
                .andExpect(status().isOk());

        JsonNode detail = getJson(token, "/api/admin/clusters/" + clusterId);
        assertEquals("active", detail.get("status").asText());
        assertFalse(detail.get("lastReviewedAt").isNull());
        assertFalse(detail.get("reviewDueAt").isNull());
        assertTrue(detail.get("reviewDueAt").asText().compareTo(detail.get("lastReviewedAt").asText()) > 0);

        long freshId = createCluster(token, domainId, "簇D2" + suffix);
        mvc.perform(post("/api/admin/clusters/" + freshId + "/status").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "active"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_STATUS_ILLEGAL"));

        mvc.perform(post("/api/admin/clusters/" + clusterId + "/status").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "reviewed"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_STATUS_ILLEGAL"));
    }

    @Test
    void mergeRetiresSourceAndMovesAllKpToTarget() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain("域E" + suffix, "active");
        long sourceId = createCluster(token, domainId, "源簇" + suffix);
        long targetId = createCluster(token, domainId, "目标簇" + suffix);
        insertKp(sourceId, "源题一" + suffix, "reviewed");
        insertKp(sourceId, "源题二" + suffix, "retired");
        insertKp(targetId, "目标题" + suffix, "draft");

        mvc.perform(post("/api/admin/clusters/" + sourceId + "/merge").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("targetId", targetId))))
                .andExpect(status().isOk());

        JsonNode sourceDetail = getJson(token, "/api/admin/clusters/" + sourceId);
        assertEquals("retired", sourceDetail.get("status").asText());
        assertEquals(targetId, sourceDetail.get("mergedInto").asLong());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_main WHERE cluster_id = ?", Long.class, sourceId));

        JsonNode targetDetail = getJson(token, "/api/admin/clusters/" + targetId);
        assertEquals(3, targetDetail.get("kpList").size());
        assertTrue(containsKpTitle(targetDetail.get("kpList"), "源题一" + suffix));
        assertTrue(containsKpTitle(targetDetail.get("kpList"), "源题二" + suffix));

        JsonNode row = findByName(
                getJson(token, "/api/admin/clusters?domainId=" + domainId).get("list"), "目标簇" + suffix);
        assertNotNull(row);
        assertEquals(2, row.get("kpCount").asLong());
    }

    @Test
    void mergeRejectsSelfAndRetiredTarget() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain("域F" + suffix, "active");
        long helperId = createCluster(token, domainId, "回收簇" + suffix);
        long retireMeId = createCluster(token, domainId, "将被并簇" + suffix);
        mvc.perform(post("/api/admin/clusters/" + retireMeId + "/merge").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("targetId", helperId))))
                .andExpect(status().isOk());
        long otherId = createCluster(token, domainId, "普通簇" + suffix);

        mvc.perform(post("/api/admin/clusters/" + otherId + "/merge").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("targetId", otherId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_MERGE_INVALID"));

        mvc.perform(post("/api/admin/clusters/" + otherId + "/merge").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("targetId", retireMeId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_MERGE_INVALID"));
    }

    @Test
    void updateChangesNameDomainAndMdContent() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain("域G" + suffix, "active");
        long otherDomainId = insertDomain("域H" + suffix, "active");
        long clusterId = createCluster(token, domainId, "原簇" + suffix);

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "改名簇" + suffix);
        payload.put("domainId", otherDomainId);
        payload.put("description", "新描述" + suffix);
        payload.put("zeroShotMd", "# 零样提示");
        payload.put("referenceSolutionMd", "## 参考解");
        mvc.perform(put("/api/admin/clusters/" + clusterId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(payload)))
                .andExpect(status().isOk());

        JsonNode detail = getJson(token, "/api/admin/clusters/" + clusterId);
        assertEquals("改名簇" + suffix, detail.get("name").asText());
        assertEquals(otherDomainId, detail.get("domainId").asLong());
        assertEquals("新描述" + suffix, detail.get("description").asText());
        assertEquals("# 零样提示", detail.get("zeroShotMd").asText());
        assertEquals("## 参考解", detail.get("referenceSolutionMd").asText());

        long disabledId = insertDomain("域I" + suffix, "disabled");
        mvc.perform(put("/api/admin/clusters/" + clusterId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("domainId", disabledId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_DOMAIN_INVALID"));
    }

    @Test
    void adminClusterEndpointsRejectAnonymousAndCustomer() throws Exception {
        mvc.perform(get("/api/admin/clusters"))
                .andExpect(status().isUnauthorized());

        String customerToken = customerToken();
        mvc.perform(get("/api/admin/clusters").header(BEARER, "Bearer " + customerToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/clusters").header(BEARER, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("domainId", 1, "name", "越权簇"))))
                .andExpect(status().isUnauthorized());
    }

    private long insertDomain(String name, String status) {
        jdbc.update("INSERT INTO skill_domain (name, description, sort_order, status) VALUES (?, NULL, 0, ?)",
                name, status);
        return jdbc.queryForObject("SELECT id FROM skill_domain WHERE name = ?", Long.class, name);
    }

    private long insertKp(long clusterId, String title, String status) {
        jdbc.update("INSERT INTO kp_main (cluster_id, sub_type, title, variants, followups, rubric, samples, "
                        + "level_req, baseline_levels, difficulty, status) "
                        + "VALUES (?, 'default', ?, '[]', '[]', '[]', '{}', '{}', '', 1, ?)",
                clusterId, title, status);
        return jdbc.queryForObject("SELECT id FROM kp_main WHERE title = ?", Long.class, title);
    }

    private long createCluster(String token, long domainId, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/clusters").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("domainId", domainId, "name", name))))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
    }

    private String staffToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        return field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
    }

    private String customerToken() throws Exception {
        String username = "kb" + System.nanoTime();
        mvc.perform(post("/api/auth/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk());
        MvcResult login = mvc.perform(post("/api/auth/customer/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk())
                .andReturn();
        return field(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
    }

    private JsonNode getJson(String token, String uri) throws Exception {
        MvcResult result = mvc.perform(get(uri).header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode findByName(JsonNode list, String name) {
        for (JsonNode node : list) {
            if (name.equals(node.get("name").asText())) {
                return node;
            }
        }
        return null;
    }

    private boolean containsKpTitle(JsonNode kpList, String title) {
        for (JsonNode kp : kpList) {
            if (title.equals(kp.get("title").asText())) {
                return true;
            }
        }
        return false;
    }

    private String body(Map<String, ?> map) throws Exception {
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
