package com.zhiji.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 同题归一与知识地图集成测试（开发计划 T7 验收 / B端管理台PRD 十四-2 归一部分）：
 * 三层漏斗（结构强命中 / exact 跳 LLM / 中间带 LLM 精判 / 灰带路径投票）、既有归一题吸收、
 * 知识点映射与解除、语料簇聚类与命名、确认 / 拆分回退 / 簇合并编排、覆盖率统计、
 * 素材删除吸收拦截、鉴权隔离。LLM 走 ScriptedLlmTransport（可控向量与响应，不触网）。
 */
@SpringBootTest
@ActiveProfiles("test")
class CanonicalPipelineTest {

    private static final String BEARER = "Authorization";

    private static final String FORM = "{\"form\":\"question_bank\",\"confidence\":\"high\",\"reason\":\"问答结构\"}";

    private static final String JUDGE_SAME = "{\"judgments\":[{\"index\":0,\"same\":true,\"reason\":\"同题\"}]}";

    private static final String JUDGE_DIFFERENT = "{\"judgments\":[{\"index\":0,\"same\":false,\"reason\":\"不同题\"}]}";

    private static final String NAMING = "{\"names\":[{\"index\":0,\"name\":\"HashMap 机制\",\"domain\":\"测试域\"}]}";

    private static final String NAMING_TWO = "{\"names\":["
            + "{\"index\":0,\"name\":\"HashMap 机制\",\"domain\":\"测试域\"},"
            + "{\"index\":1,\"name\":\"缓存机制\",\"domain\":\"测试域\"}]}";

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        ScriptedLlmTransport scriptedLlmTransport() {
            return new ScriptedLlmTransport();
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ScriptedLlmTransport llm;

    MockMvc mvc;

    JsonMapper om = new JsonMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM kp_canonical");
        jdbc.update("DELETE FROM kp_candidate");
        jdbc.update("DELETE FROM kp_source_doc");
        jdbc.update("INSERT OR IGNORE INTO skill_domain (name, description, sort_order, status) "
                + "VALUES ('测试域', 'T7 集成测试域', 99, 'active')");
        // 合并目标簇直插无幂等守卫，跨运行残留会翻倍；直接删会撞 merged_into 自引用 FK——先断指针再删
        jdbc.update("UPDATE kp_cluster SET merged_into = NULL "
                + "WHERE merged_into IN (SELECT id FROM kp_cluster WHERE name = 'T7 合并目标簇')");
        jdbc.update("DELETE FROM kp_cluster WHERE name = 'T7 合并目标簇'");
        llm.clearVectors();
        llm.respond("{}");
    }

    @Test
    void strongStructuralMergeCreatesConfirmedCanonicalAndCluster() throws Exception {
        String token = staffToken();
        long docId = importDoc(token, "题库.md", "# Java 题库\n\n## HashMap 的底层实现是什么\n\n数组加链表。\n\n"
                + "## HashMap 的底层实现是什么\n\n数组加链表，超 8 转红黑树。");
        llm.respond(FORM);
        parseDoc(token, docId);
        splitDoc(token, docId);

        llm.respond(NAMING);
        JsonNode summary = runMap(token);
        assertEquals(1, summary.get("newCanonicals").asInt());
        assertEquals(0, summary.get("absorbedItems").asInt());
        assertEquals(1, summary.get("newClusters").asInt());
        assertTrue(summary.get("embeddingOk").asBoolean());

        JsonNode list = canonicals(token, "");
        assertEquals(1, list.size());
        JsonNode canonical = list.get(0);
        assertEquals("HashMap 的底层实现是什么", canonical.get("title").asText());
        assertEquals("confirmed", canonical.get("status").asText());
        assertEquals("structural", canonical.get("judgeBasis").asText());
        assertEquals(1, canonical.get("coverageCount").asInt());
        assertTrue(canonical.get("clusterId").asLong() > 0, "新归一题应已聚入语料簇");

        JsonNode detail = getJson(token, "/api/admin/knowledge-map/canonicals/" + canonical.get("id").asLong());
        assertEquals(2, detail.get("sources").size());
        assertEquals(docId, detail.get("sources").get(0).get("docId").asLong());
    }

    @Test
    void exactVectorMergeSkipsJudgeLlm() throws Exception {
        String token = staffToken();
        importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 底层实现", "数组加链表转红黑树");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 底层实现", vec(2, 0));

        llm.respond(NAMING);
        JsonNode summary = runMap(token);
        assertEquals(1, summary.get("newCanonicals").asInt());
        assertEquals(1, llm.chatCalls(), "exact 归并应跳过精判 LLM，只有簇命名一次调用");

        JsonNode canonical = canonicals(token, "").get(0);
        assertEquals("confirmed", canonical.get("status").asText());
        assertEquals("exact", canonical.get("judgeBasis").asText());
        assertEquals(2, canonical.get("coverageCount").asInt(), "两个独立文档 → 覆盖源数 2");
    }

    @Test
    void middleBandJudgedSameLandsPendingWithLlmBasis() throws Exception {
        String token = staffToken();
        importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 底层是如何实现的", "数组加链表");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 底层是如何实现的", vec(0.87, Math.sqrt(1 - 0.87 * 0.87)));

        llm.script(JUDGE_SAME, NAMING);
        JsonNode summary = runMap(token);
        assertEquals(1, summary.get("newCanonicals").asInt());

        JsonNode canonical = canonicals(token, "").get(0);
        assertEquals("pending", canonical.get("status").asText());
        assertEquals("llm", canonical.get("judgeBasis").asText());
        assertEquals(2, canonical.get("coverageCount").asInt());
    }

    @Test
    void middleBandJudgedDifferentStaysLoose() throws Exception {
        String token = staffToken();
        importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 树化阈值是多少", "链表长度 8 且容量 64");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 树化阈值是多少", vec(0.87, Math.sqrt(1 - 0.87 * 0.87)));

        llm.script(JUDGE_DIFFERENT);
        JsonNode summary = runMap(token);
        assertEquals(0, summary.get("newCanonicals").asInt());
        assertEquals(0, canonicals(token, "").size());
        assertEquals(2, summary.get("looseCount").asInt());
    }

    @Test
    void secondRunAbsorbsIntoExistingCanonicalAndRecomputesCoverage() throws Exception {
        String token = staffToken();
        importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 底层实现", "数组加链表");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 底层实现", vec(2, 0));
        llm.respond(NAMING);
        runMap(token);

        long docC = importSingleQuestionDoc(token, "库C.md", "HashMap 底层实现原理", "数组加链表");
        llm.setVector("HashMap 底层实现原理", vec(3, 0));
        llm.respond("{}");
        JsonNode summary = runMap(token);
        assertEquals(0, summary.get("newCanonicals").asInt());
        assertEquals(1, summary.get("absorbedItems").asInt());

        JsonNode canonical = canonicals(token, "").get(0);
        assertEquals(3, canonical.get("coverageCount").asInt());
        JsonNode detail = getJson(token, "/api/admin/knowledge-map/canonicals/" + canonical.get("id").asLong());
        assertEquals(3, detail.get("sources").size());
        assertEquals(docC, detail.get("sources").get(2).get("docId").asLong());
    }

    @Test
    void canonicalMatchingActiveKpMarksBuiltAndUnbuildReverts() throws Exception {
        String token = staffToken();
        long kpId = seedActiveKp("归一映射样板题");
        importSingleQuestionDoc(token, "库A.md", "归一映射样板题", "标准答案要点");
        importSingleQuestionDoc(token, "库B.md", "归一映射样板题是什么", "同题答案");
        llm.setVector("归一映射样板题", vec(1, 0));
        llm.setVector("归一映射样板题是什么", vec(1, 0));

        llm.respond(NAMING);
        runMap(token);

        JsonNode canonical = canonicals(token, "").get(0);
        assertEquals("built", canonical.get("status").asText());
        assertEquals(kpId, canonical.get("builtKpId").asLong());

        JsonNode coverage = getJson(token, "/api/admin/knowledge-map/coverage");
        assertEquals(1, coverage.get("builtCount").asInt());

        // 解除映射 → 回 confirmed，可再拆分
        mvc.perform(post("/api/admin/knowledge-map/canonicals/" + canonical.get("id").asLong() + "/unbuild")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        JsonNode reverted = canonicals(token, "").get(0);
        assertEquals("confirmed", reverted.get("status").asText());
        assertTrue(reverted.get("builtKpId").isNull());
    }

    @Test
    void splitAllDissolvesAndReturnsCandidatesToLoose() throws Exception {
        String token = staffToken();
        importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 底层实现", "数组加链表");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 底层实现", vec(2, 0));
        llm.respond(NAMING);
        runMap(token);
        long canonicalId = canonicals(token, "").get(0).get("id").asLong();

        JsonNode result = postJson(token, "/api/admin/knowledge-map/canonicals/" + canonicalId + "/split",
                Map.of("mode", "all"));
        assertTrue(result.get("dissolved").asBoolean());
        assertEquals(2, result.get("returned").asInt());
        assertEquals(0, canonicals(token, "").size());

        // 退回散题后重新 run → 重新聚出归一题（回到候选池重新研判的闭环）
        llm.respond(NAMING);
        JsonNode summary = runMap(token);
        assertEquals(1, summary.get("newCanonicals").asInt());
    }

    @Test
    void splitPartialKeepsRemainingMembers() throws Exception {
        String token = staffToken();
        long docId = importDoc(token, "题库.md", "# Java 题库\n\n## HashMap 的底层实现是什么\n\n答案一。\n\n"
                + "## HashMap 的底层实现是什么\n\n答案二。\n\n## HashMap 的底层实现是什么\n\n答案三。");
        llm.respond(FORM);
        parseDoc(token, docId);
        splitDoc(token, docId);
        llm.respond(NAMING);
        runMap(token);
        JsonNode canonical = canonicals(token, "").get(0);
        long canonicalId = canonical.get("id").asLong();
        JsonNode detail = getJson(token, "/api/admin/knowledge-map/canonicals/" + canonicalId);
        long firstCandidateId = detail.get("sources").get(0).get("candidateId").asLong();

        JsonNode result = postJson(token, "/api/admin/knowledge-map/canonicals/" + canonicalId + "/split",
                Map.of("mode", "partial", "candidateIds", List.of(firstCandidateId)));
        assertTrue(!result.get("dissolved").asBoolean());
        assertEquals(1, result.get("returned").asInt());

        JsonNode after = canonicals(token, "").get(0);
        assertEquals(1, after.get("coverageCount").asInt());
        JsonNode afterDetail = getJson(token, "/api/admin/knowledge-map/canonicals/" + canonicalId);
        assertEquals(2, afterDetail.get("sources").size());
    }

    @Test
    void builtCanonicalRejectsSplitAndConfirm() throws Exception {
        String token = staffToken();
        seedActiveKp("归一映射样板题");
        importSingleQuestionDoc(token, "库A.md", "归一映射样板题", "答案");
        importSingleQuestionDoc(token, "库B.md", "归一映射样板题是什么", "答案");
        llm.setVector("归一映射样板题", vec(1, 0));
        llm.setVector("归一映射样板题是什么", vec(1, 0));
        llm.respond(NAMING);
        runMap(token);
        long canonicalId = canonicals(token, "").get(0).get("id").asLong();

        mvc.perform(post("/api/admin/knowledge-map/canonicals/" + canonicalId + "/split")
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"all\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANONICAL_IMMUTABLE"));
        mvc.perform(post("/api/admin/knowledge-map/canonicals/" + canonicalId + "/confirm")
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void confirmMovesPendingToConfirmedOnce() throws Exception {
        String token = staffToken();
        importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 底层是如何实现的", "数组加链表");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 底层是如何实现的", vec(0.87, Math.sqrt(1 - 0.87 * 0.87)));
        llm.script(JUDGE_SAME, NAMING);
        runMap(token);
        long canonicalId = canonicals(token, "").get(0).get("id").asLong();

        mvc.perform(post("/api/admin/knowledge-map/canonicals/" + canonicalId + "/confirm")
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        assertEquals("confirmed", canonicals(token, "").get(0).get("status").asText());
        mvc.perform(post("/api/admin/knowledge-map/canonicals/" + canonicalId + "/confirm")
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clusterMergeMovesCanonicalsAndRetiresSource() throws Exception {
        String token = staffToken();
        long docId = importDoc(token, "题库.md", "# Java 题库\n\n## HashMap 的底层实现是什么\n\n答案一。\n\n"
                + "## HashMap 的底层实现是什么\n\n答案二。");
        llm.respond(FORM);
        parseDoc(token, docId);
        splitDoc(token, docId);
        llm.respond(NAMING);
        runMap(token);
        long sourceClusterId = canonicals(token, "").get(0).get("clusterId").asLong();

        long domainId = jdbc.queryForObject("SELECT id FROM skill_domain WHERE name = '测试域'", Long.class);
        jdbc.update("INSERT INTO kp_cluster (domain_id, name, status) VALUES (?, 'T7 合并目标簇', 'draft')", domainId);
        long targetClusterId = jdbc.queryForObject(
                "SELECT id FROM kp_cluster WHERE name = 'T7 合并目标簇'", Long.class);

        mvc.perform(post("/api/admin/knowledge-map/clusters/" + sourceClusterId + "/merge")
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + targetClusterId + "}"))
                .andExpect(status().isOk());

        assertEquals(targetClusterId, canonicals(token, "").get(0).get("clusterId").asLong());
        assertEquals("retired", getJson(token, "/api/admin/clusters/" + sourceClusterId).get("status").asText());

        mvc.perform(post("/api/admin/knowledge-map/clusters/" + targetClusterId + "/merge")
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + targetClusterId + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void coverageCountsClustersAndUnclustered() throws Exception {
        String token = staffToken();
        importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 底层实现", "数组加链表");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 底层实现", vec(2, 0));
        llm.respond(NAMING);
        runMap(token);

        JsonNode coverage = getJson(token, "/api/admin/knowledge-map/coverage");
        assertEquals(1, coverage.get("canonicalCount").asInt());
        assertEquals(1, coverage.get("clusterCount").asInt());
        assertEquals(0, coverage.get("builtCount").asInt());
        assertEquals(0, coverage.get("pendingCount").asInt());
        assertEquals(0, coverage.get("unclusteredCount").asInt());
        assertEquals(1, coverage.get("clusters").size());
        assertTrue(coverage.get("domainCount").asInt() >= 1);

        JsonNode tree = getJson(token, "/api/admin/knowledge-map/tree");
        assertTrue(tree.get("domains").size() >= 1);
        assertTrue(tree.get("unclusteredCount").asInt() == 0);
    }

    @Test
    void repeatRunIsIdempotent() throws Exception {
        String token = staffToken();
        importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 底层实现", "数组加链表");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 底层实现", vec(2, 0));
        llm.respond(NAMING);
        runMap(token);

        llm.respond(NAMING);
        JsonNode summary = runMap(token);
        assertEquals(0, summary.get("newCanonicals").asInt());
        assertEquals(0, summary.get("newClusters").asInt());
        assertEquals(1, canonicals(token, "").size());
    }

    @Test
    void docDeletionBlockedWhenCandidatesAbsorbed() throws Exception {
        String token = staffToken();
        long docId = importSingleQuestionDoc(token, "库A.md", "HashMap 的底层实现是什么", "数组加链表");
        importSingleQuestionDoc(token, "库B.md", "HashMap 底层实现", "数组加链表");
        llm.setVector("HashMap 的底层实现是什么", vec(1, 0));
        llm.setVector("HashMap 底层实现", vec(2, 0));
        llm.respond(NAMING);
        runMap(token);

        mvc.perform(delete("/api/admin/docs/" + docId).header(BEARER, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_CANDIDATES_ABSORBED"));
    }

    @Test
    void suggestNameReturnsLlmSuggestionWithoutPersisting() throws Exception {
        String token = staffToken();
        long docId = importDoc(token, "题库.md", "# Java 题库\n\n## HashMap 的底层实现是什么\n\n答案一。\n\n"
                + "## HashMap 的底层实现是什么\n\n答案二。");
        llm.respond(FORM);
        parseDoc(token, docId);
        splitDoc(token, docId);
        // 命名走降级簇名（respond 默认 {}），clusterAndName 建簇后 suggest-name 再显式要建议
        llm.respond("{}");
        runMap(token);
        long clusterId = canonicals(token, "").get(0).get("clusterId").asLong();

        llm.respond(NAMING);
        JsonNode suggestion = postJsonRaw(token,
                "/api/admin/knowledge-map/clusters/" + clusterId + "/suggest-name", Map.of());
        assertEquals("HashMap 机制", suggestion.get("name").asText());
        assertNotNull(suggestion.get("domainId").asLong());
    }

    @Test
    void customerTokenRejectedOnKnowledgeMap() throws Exception {
        String token = customerToken();
        mvc.perform(get("/api/admin/knowledge-map/coverage").header(BEARER, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /* ================= helpers ================= */

    private float[] vec(double... components) {
        float[] v = new float[8];
        double norm = 0;
        for (int i = 0; i < components.length; i++) {
            v[i] = (float) components[i];
            norm += v[i] * v[i];
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / Math.sqrt(norm));
        }
        return v;
    }

    /** 单题文档：无标题结构 → 拆题走 LLM 兜底（stub 脚本返回 items），每文档恰好 1 条候选。 */
    private long importSingleQuestionDoc(String token, String fileName, String question, String answer)
            throws Exception {
        long docId = importDoc(token, fileName, "# 题库\n\n" + question + "\n\n" + answer);
        llm.respond(FORM);
        parseDoc(token, docId);
        llm.respond("{\"items\":[{\"question\":\"" + question + "\",\"answer\":\"" + answer + "\"}]}");
        splitDoc(token, docId);
        return docId;
    }

    private long importDoc(String token, String fileName, String content) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/admin/docs/import")
                        .file(new MockMultipartFile("files", fileName, MediaType.TEXT_PLAIN_VALUE,
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("docs").get(0).get("id").asLong();
    }

    private void parseDoc(String token, long docId) throws Exception {
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void splitDoc(String token, long docId) throws Exception {
        mvc.perform(post("/api/admin/docs/" + docId + "/split").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private JsonNode runMap(String token) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/knowledge-map/run").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode canonicals(String token, String query) throws Exception {
        JsonNode node = getJson(token, "/api/admin/knowledge-map/canonicals" + query);
        return node.get("list");
    }

    private JsonNode getJson(String token, String uri) throws Exception {
        MvcResult result = mvc.perform(get(uri).header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postJson(String token, String uri, Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post(uri)
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postJsonRaw(String token, String uri, Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post(uri)
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long seedActiveKp(String title) {
        jdbc.update("INSERT INTO kp_cluster (domain_id, name, status) "
                + "SELECT id, 'T7 簇', 'draft' FROM skill_domain WHERE name = '测试域' "
                + "AND NOT EXISTS (SELECT 1 FROM kp_cluster WHERE name = 'T7 簇')");
        jdbc.update("INSERT INTO kp_main (cluster_id, sub_type, title, difficulty, status) "
                + "SELECT c.id, 'default', ?, 3, 'active' FROM kp_cluster c WHERE c.name = 'T7 簇' "
                + "AND NOT EXISTS (SELECT 1 FROM kp_main WHERE title = ?)", title, title);
        return jdbc.queryForObject("SELECT id FROM kp_main WHERE title = ?", Long.class, title);
    }

    private String staffToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        return field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
    }

    private String customerToken() throws Exception {
        String username = "kmc" + System.nanoTime();
        mvc.perform(post("/api/auth/customer/register").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk());
        MvcResult login = mvc.perform(post("/api/auth/customer/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", "Passw0rd",
                                "captchaId", "test", "captchaCode", "test"))))
                .andExpect(status().isOk())
                .andReturn();
        return field(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
    }

    private String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name);
        }
        return matcher.group(1);
    }
}
