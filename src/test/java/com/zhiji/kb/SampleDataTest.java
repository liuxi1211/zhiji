package com.zhiji.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.common.exception.BizException;
import com.zhiji.kb.service.KpStructureValidator;
import com.zhiji.kb.service.KpStructureValidator.FollowupLayer;
import com.zhiji.kb.service.KpStructureValidator.KpSavePayload;
import com.zhiji.kb.service.KpStructureValidator.LevelReq;
import com.zhiji.kb.service.KpStructureValidator.RubricGroup;
import com.zhiji.kb.service.KpStructureValidator.RubricPoint;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 样板种子数据（db/sample-data.sql，知识库PRD 12.1 阶段 2）验收：
 * 灌库幂等、10 条样板计数、结构与结构校验器全通过、外键完整、
 * Markdown 三类语法覆盖、sub_type 分布、B 端列表接口可消费。
 * 种子按自然键幂等，共享测试库中先执行灌入再按 title 精确集合断言。
 */
@SpringBootTest
@ActiveProfiles("test")
class SampleDataTest {

    private static final List<String> SAMPLE_TITLES = List.of(
            "ES 写入一条数据的完整流程是什么？",
            "为什么 Elasticsearch 是近实时的？",
            "Elasticsearch 写入为什么快？",
            "Elasticsearch 的 refresh 和 flush 有什么区别？",
            "translog 在 Elasticsearch 写入中起什么作用？",
            "Elasticsearch 段合并是怎么发生的？",
            "倒排索引在 Elasticsearch 里是怎么构成的？",
            "主分片和副本分片在写入时是怎么交互的？",
            "讲讲你负责过的最有挑战的项目",
            "设计一个支撑百万级 QPS 的商品详情页系统");

    private static final String BEARER = "Authorization";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    JsonMapper om = new JsonMapper();

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        runSeedScript();
    }

    private void runSeedScript() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/sample-data.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(jdbc.getDataSource());
    }

    @Test
    void seedLoadsExactlyTenKpByIdempotentNaturalKey() {
        String placeholders = String.join(",", SAMPLE_TITLES.stream().map(t -> "?").toList());
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_main WHERE title IN (" + placeholders + ")", Long.class,
                SAMPLE_TITLES.toArray());
        assertEquals(10L, count);
    }

    @Test
    void seedIsIdempotentOnRerun() {
        String placeholders = String.join(",", SAMPLE_TITLES.stream().map(t -> "?").toList());
        runSeedScript();
        runSeedScript();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_main WHERE title IN (" + placeholders + ")", Long.class,
                SAMPLE_TITLES.toArray());
        assertEquals(10L, count);
        assertEquals(3L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_cluster WHERE name IN ('ES 写入链路','高并发系统设计','通用项目叙事')",
                Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_domain WHERE name IN ('Elasticsearch','Java 后端')", Long.class));
    }

    @Test
    void allSamplesAreActiveWithReviewTimesAndJsonStructureValid() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, sub_type, difficulty, variants, followups, rubric, samples, level_req, "
                        + "baseline_levels, status, last_reviewed_at, review_due_at FROM kp_main WHERE title IN ("
                        + SAMPLE_TITLES.stream().map(t -> "'" + t + "'").reduce((a, b) -> a + "," + b).orElse("") + ")");
        assertEquals(10, rows.size());

        Set<String> seenSeniorPoints = new LinkedHashSet<>();
        int defaultCount = 0;
        int narrativeCount = 0;
        int designChainCount = 0;
        for (Map<String, Object> row : rows) {
            String title = (String) row.get("title");
            assertEquals("active", row.get("status"), title);
            assertNotNull(row.get("last_reviewed_at"), title);
            assertNotNull(row.get("review_due_at"), title);

            String subType = (String) row.get("sub_type");
            switch (subType) {
                case "default" -> defaultCount++;
                case "narrative" -> narrativeCount++;
                case "design_chain" -> designChainCount++;
                default -> fail("未知 sub_type：" + subType);
            }

            String baseline = (String) row.get("baseline_levels");
            List<String> marked = new ArrayList<>();
            for (String part : baseline.split(",")) {
                if (!part.isBlank()) {
                    marked.add(part.trim());
                }
            }
            assertTrue(marked.size() >= 2, title + " baseline 已标档位不足 2：" + baseline);
            assertTrue(marked.contains("mid"), title + " baseline 未含 mid：" + baseline);

            JsonNode followups = om.readTree((String) row.get("followups"));
            JsonNode rubric = om.readTree((String) row.get("rubric"));
            JsonNode samples = om.readTree((String) row.get("samples"));
            JsonNode levelReq = om.readTree((String) row.get("level_req"));
            assertTrue(om.readTree((String) row.get("variants")).isArray(), title);

            Set<String> layerKeys = new LinkedHashSet<>();
            layerKeys.add("main");
            for (JsonNode layer : followups) {
                layerKeys.add(layer.get("layerKey").asText());
                assertEquals("active", layer.get("status").asText(), title);
            }
            int stopMarked = 0;
            for (Map.Entry<String, JsonNode> e : levelReq.properties()) {
                assertTrue(Set.of("junior", "mid", "senior", "staff", "expert").contains(e.getKey()),
                        title + " 职级键非法：" + e.getKey());
                JsonNode req = e.getValue();
                assertTrue(req.has("required"), title);
                String stopLayer = req.get("stopLayer").isNull() ? null : req.get("stopLayer").asText();
                if (stopLayer != null && !stopLayer.isBlank()) {
                    stopMarked++;
                    assertTrue(layerKeys.contains(stopLayer), title + " stopLayer 未知：" + stopLayer);
                }
            }
            assertTrue(stopMarked >= 2, title + " 标定 stop_layer 的档位不足 2");

            assertEquals(3, samples.size(), title);
            assertTrue(samples.has("pass") && samples.has("good") && samples.has("excellent"), title);

            for (JsonNode group : rubric) {
                assertTrue(layerKeys.contains(group.get("layerKey").asText()),
                        title + " rubric 组指向未知层：" + group.get("layerKey").asText());
                for (JsonNode point : group.get("points")) {
                    assertNotNull(point.get("pointId").asText(), title);
                    if ("senior".equals(point.get("level").asText())) {
                        seenSeniorPoints.add(point.get("pointId").asText());
                    }
                }
            }
            validateWithStructureValidator(row, title, followups, rubric, samples, levelReq);
        }

        assertEquals(8, defaultCount);
        assertEquals(1, narrativeCount);
        assertEquals(1, designChainCount);
        assertTrue(seenSeniorPoints.size() >= 2, "senior 级要点不足（至少 2 条知识点出现）");
        Set<String> distinctDifficulty = new LinkedHashSet<>();
        jdbc.queryForList("SELECT DISTINCT difficulty FROM kp_main WHERE title IN ("
                + SAMPLE_TITLES.stream().map(t -> "'" + t + "'").reduce((a, b) -> a + "," + b).orElse("") + ")")
                .forEach(r -> distinctDifficulty.add(String.valueOf(r.get("difficulty"))));
        assertTrue(distinctDifficulty.containsAll(Set.of("2", "3", "4", "5")),
                "难度分布需覆盖 2/3/4/5，实际 " + distinctDifficulty);
    }

    private void validateWithStructureValidator(Map<String, Object> row, String title,
            JsonNode followups, JsonNode rubric, JsonNode samples, JsonNode levelReq) {
        List<FollowupLayer> layers = new ArrayList<>();
        for (JsonNode layer : followups) {
            layers.add(new FollowupLayer(layer.get("layerKey").asText(), layer.get("dir").asText(),
                    layer.get("text").asText(), textOrNull(layer.get("expect")),
                    textOrNull(layer.get("stopCondition")), layer.get("status").asText()));
        }
        List<RubricGroup> groups = new ArrayList<>();
        for (JsonNode group : rubric) {
            List<RubricPoint> points = new ArrayList<>();
            for (JsonNode point : group.get("points")) {
                points.add(new RubricPoint(point.get("pointId").asText(), point.get("level").asText(),
                        point.get("text").asText()));
            }
            groups.add(new RubricGroup(group.get("layerKey").asText(), points));
        }
        Map<String, String> samplesMap = new LinkedHashMap<>();
        samples.propertyNames().forEach(k -> samplesMap.put(k, samples.get(k).asText()));
        Map<String, LevelReq> levelReqMap = new LinkedHashMap<>();
        levelReq.propertyNames().forEach(k -> {
            JsonNode req = levelReq.get(k);
            levelReqMap.put(k, new LevelReq(req.get("required").asBoolean(),
                    req.get("stopLayer").isNull() ? null : req.get("stopLayer").asText()));
        });
        List<String> variants = new ArrayList<>();
        om.readTree((String) row.get("variants")).forEach(v -> variants.add(v.asText()));

        Long clusterId = ((Number) jdbc.queryForObject(
                "SELECT cluster_id FROM kp_main WHERE title = ?", Long.class, title)).longValue();
        KpSavePayload payload = new KpSavePayload(clusterId, (String) row.get("sub_type"), title,
                ((Number) row.get("difficulty")).intValue(), variants, (String) row.get("narrative_framework"),
                layers, groups, samplesMap, levelReqMap, List.of());
        try {
            KpStructureValidator.validate(payload);
        } catch (BizException e) {
            fail(title + " 结构校验未通过：" + e.getMessage() + " fields=" + e.getFields());
        }
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    @Test
    void foreignKeysResolveToDomainAndClusterNames() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT k.title, d.name AS domain_name, c.name AS cluster_name FROM kp_main k "
                        + "JOIN kp_cluster c ON c.id = k.cluster_id JOIN skill_domain d ON d.id = c.domain_id "
                        + "WHERE k.title IN (" + SAMPLE_TITLES.stream().map(t -> "'" + t + "'")
                        .reduce((a, b) -> a + "," + b).orElse("") + ")");
        assertEquals(10, rows.size());
        for (Map<String, Object> row : rows) {
            assertNotNull(row.get("domain_name"), (String) row.get("title"));
            assertNotNull(row.get("cluster_name"), (String) row.get("title"));
        }
        Long clusterCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_cluster WHERE status = 'active' AND name IN "
                        + "('ES 写入链路','高并发系统设计','通用项目叙事')", Long.class);
        assertEquals(3L, clusterCount);
    }

    @Test
    void markdownSyntaxCoverageAcrossSamplesAndClusterDocs() {
        StringBuilder all = new StringBuilder();
        jdbc.queryForList("SELECT zero_shot_md, reference_solution_md FROM kp_cluster WHERE name IN "
                + "('ES 写入链路','高并发系统设计','通用项目叙事')")
                .forEach(r -> {
                    all.append(r.get("zero_shot_md")).append('\n');
                    all.append(r.get("reference_solution_md")).append('\n');
                });
        jdbc.queryForList("SELECT samples FROM kp_main WHERE title IN ("
                + SAMPLE_TITLES.stream().map(t -> "'" + t + "'").reduce((a, b) -> a + "," + b).orElse("") + ")")
                .forEach(r -> all.append((String) r.get("samples")).append('\n'));
        String text = all.toString();
        assertTrue(text.contains("```"), "缺少代码块语法");
        assertTrue(text.contains("```mermaid"), "缺少 mermaid 代码块");
        assertTrue(text.contains("|"), "缺少 Markdown 表格");
        assertTrue(text.contains("```json"), "缺少 json 代码块");
        assertTrue(text.contains("```sql"), "缺少 sql 代码块");
    }

    @Test
    void narrativeSampleContainsNoPersonalContent() {
        String samples = jdbc.queryForObject(
                "SELECT samples FROM kp_main WHERE sub_type = 'narrative' AND title IN ("
                        + SAMPLE_TITLES.stream().map(t -> "'" + t + "'").reduce((a, b) -> a + "," + b).orElse("") + ")",
                String.class);
        assertFalse(samples.contains("我负责过"), "叙事样例不得出现第一人称具体项目");
        assertTrue(samples.contains("某电商系统"), "叙事样例应为泛化占位叙述");
    }

    @Test
    void prereqAndFreqSignalSeedsLoad() {
        Long prereqNearRealtime = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_prereq p JOIN kp_main a ON a.id = p.kp_id "
                        + "JOIN kp_main b ON b.id = p.prereq_kp_id "
                        + "WHERE a.title = '为什么 Elasticsearch 是近实时的？' "
                        + "AND b.title = 'ES 写入一条数据的完整流程是什么？'", Long.class);
        assertEquals(1L, prereqNearRealtime);
        Long prereqWhyFast = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_prereq p JOIN kp_main a ON a.id = p.kp_id "
                        + "JOIN kp_main b ON b.id = p.prereq_kp_id "
                        + "WHERE a.title = 'Elasticsearch 写入为什么快？' "
                        + "AND b.title = '倒排索引在 Elasticsearch 里是怎么构成的？'", Long.class);
        assertEquals(1L, prereqWhyFast);
        Long freqSignals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_freq_signal s JOIN kp_main k ON k.id = s.kp_id "
                        + "WHERE s.source = 'manual' AND s.signal_level = 'high' AND k.title IN "
                        + "('ES 写入一条数据的完整流程是什么？','设计一个支撑百万级 QPS 的商品详情页系统')", Long.class);
        assertEquals(2L, freqSignals);
    }

    @Test
    void adminKpListServesSeeds() throws Exception {
        String token = staffToken();
        MvcResult result = mvc.perform(get("/api/admin/kp?size=50").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andReturn();
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(node.get("total").asLong() >= 10,
                "B 端列表应能消费种子数据，total=" + node.get("total").asLong());
    }

    private String staffToken() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/staff/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(login.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("token").asText();
    }
}
