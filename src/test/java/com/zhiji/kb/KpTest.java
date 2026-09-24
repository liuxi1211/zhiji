package com.zhiji.kb;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * 知识点 B 端接口全流程：结构落库与 pointId 分配、layer_key 白名单红线、追问层身份管理、
 * 结构校验 fields 明细、前置防环与状态机、下线合并变体去重、检索筛选分页、频率信号 upsert、
 * meta 聚合、鉴权隔离、baseline_levels 确定性重算。
 * 测试库共享（临时 SQLite），名称用 nanoTime 保证唯一。
 */
@SpringBootTest
@ActiveProfiles("test")
class KpTest {

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
    void createLandsDraftAndDetailCarriesFullStructureWithAssignedPointIds() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "握手簇" + suffix);

        Map<String, Object> payload = kpPayload(clusterId, "TCP三次握手" + suffix);
        payload.put("variants", List.of("三次握手过程"));
        payload.put("followups", List.of(layer("L1", "深入", "为什么恰好三次", "两次会让旧连接复活",
                "答出历史连接复现风险", "active")));
        payload.put("rubric", List.of(
                group("main", List.of(point(null, "basic", "能完整说出 SYN / SYN-ACK / ACK 三步"))),
                group("L1", List.of(point(null, "senior", "能解释半连接队列与 SYN Flood")))));
        payload.put("samples", Map.of("pass", "背出三步", "good", "带出状态机", "excellent", "结合抓包"));
        payload.put("levelReq", Map.of("junior", levelReq(true, "main")));

        MvcResult created = mvc.perform(post("/api/admin/kp").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(payload)))
                .andExpect(status().isOk())
                .andReturn();
        long kpId = Long.parseLong(field(created.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));

        JsonNode detail = getJson(token, "/api/admin/kp/" + kpId);
        assertEquals("TCP三次握手" + suffix, detail.get("title").asText());
        assertEquals("draft", detail.get("status").asText());
        assertEquals("握手簇" + suffix, detail.get("clusterName").asText());
        assertEquals("测试域" + suffix, detail.get("domainName").asText());
        assertEquals(1, detail.get("variants").size());
        assertEquals(1, detail.get("followups").size());
        assertEquals("L1", detail.get("followups").get(0).get("layerKey").asText());
        assertEquals("active", detail.get("followups").get(0).get("status").asText());
        assertEquals("深入", detail.get("followups").get(0).get("dir").asText());
        assertEquals(2, detail.get("rubric").size());
        assertEquals("main", detail.get("rubric").get(0).get("layerKey").asText());
        assertTrue(detail.get("rubric").get(0).get("points").get(0).get("pointId").asText()
                .matches("p[0-9a-f]{8}"));
        assertNotEquals(detail.get("rubric").get(0).get("points").get(0).get("pointId").asText(),
                detail.get("rubric").get(1).get("points").get(0).get("pointId").asText());
        assertEquals("背出三步", detail.get("samples").get("pass").asText());
        assertEquals("main", detail.get("levelReq").get("junior").get("stopLayer").asText());
        assertEquals(0, detail.get("prereqIds").size());
        assertEquals(0, detail.get("freqSignals").size());
        assertTrue(detail.get("mergedInto").isNull());
    }

    @Test
    void updateSavesButRejectsUnknownMissingLayerKeyAndUnknownPointId() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "编辑簇" + suffix);

        Map<String, Object> payload = kpPayload(clusterId, "原题" + suffix);
        payload.put("followups", List.of(layer("L1", "深入", "为什么需要", null, null, "active")));
        payload.put("rubric", List.of(
                group("main", List.of(point("paaaaaaaa", "basic", "主干要点"))),
                group("L1", List.of(point("pbbbbbbbb", "senior", "追问要点")))));
        long kpId = createKp(token, payload);

        Map<String, Object> edited = kpPayload(clusterId, "改后题" + suffix);
        edited.put("variants", List.of("变体一"));
        edited.put("followups", List.of(layer("L1", "深挖", "为什么需要（改）", "预期答案", null, "active")));
        edited.put("rubric", List.of(
                group("main", List.of(point("paaaaaaaa", "mid", "主干要点（改）"))),
                group("L1", List.of(point("pbbbbbbbb", "senior", "追问要点")))));
        mvc.perform(put("/api/admin/kp/" + kpId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(edited)))
                .andExpect(status().isOk());

        JsonNode detail = getJson(token, "/api/admin/kp/" + kpId);
        assertEquals("改后题" + suffix, detail.get("title").asText());
        assertEquals("变体一", detail.get("variants").get(0).asText());
        assertEquals("深挖", detail.get("followups").get(0).get("dir").asText());
        assertEquals("paaaaaaaa", detail.get("rubric").get(0).get("points").get(0).get("pointId").asText());
        assertEquals("mid", detail.get("rubric").get(0).get("points").get(0).get("level").asText());

        Map<String, Object> withUnknownLayer = kpPayload(clusterId, "改后题" + suffix);
        withUnknownLayer.put("followups", List.of(layer("L1", "深入", "为什么需要", null, null, "active"),
                layer("L9", "展开", "不存在的层", null, null, "active")));
        withUnknownLayer.put("rubric", payload.get("rubric"));
        mvc.perform(put("/api/admin/kp/" + kpId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(withUnknownLayer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_LAYER_KEY_UNKNOWN"))
                .andExpect(jsonPath("$.message", containsString("新增/停用层必须走对应接口")));

        Map<String, Object> withoutExistingLayer = kpPayload(clusterId, "改后题" + suffix);
        withoutExistingLayer.put("followups", List.of());
        withoutExistingLayer.put("rubric", payload.get("rubric"));
        mvc.perform(put("/api/admin/kp/" + kpId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(withoutExistingLayer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_LAYER_KEY_UNKNOWN"));

        Map<String, Object> withUnknownPoint = kpPayload(clusterId, "改后题" + suffix);
        withUnknownPoint.put("followups", List.of(layer("L1", "深入", "为什么需要", null, null, "active")));
        withUnknownPoint.put("rubric", List.of(
                group("main", List.of(point("pzzzzzzz", "basic", "未知身份要点"))),
                group("L1", List.of(point("pbbbbbbbb", "senior", "追问要点")))));
        mvc.perform(put("/api/admin/kp/" + kpId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(withUnknownPoint)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_POINT_ID_UNKNOWN"));
    }

    @Test
    void followupLayerKeysIncrementWithDisabledSlotAndDisableRules() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "追问簇" + suffix);
        long kpId = createKp(token, kpPayload(clusterId, "追问题" + suffix));

        mvc.perform(post("/api/admin/kp/" + kpId + "/followup-layers").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("dir", "深入", "text", "追问一"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layerKey").value("L1"));
        mvc.perform(post("/api/admin/kp/" + kpId + "/followup-layers").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("dir", "展开", "text", "追问二"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layerKey").value("L2"));

        mvc.perform(post("/api/admin/kp/" + kpId + "/followup-layers/L2/disable")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/admin/kp/" + kpId + "/followup-layers").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("dir", "迁移", "text", "追问三"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layerKey").value("L3"));

        mvc.perform(post("/api/admin/kp/" + kpId + "/followup-layers/main/disable")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_LAYER_KEY_INVALID"));
        mvc.perform(post("/api/admin/kp/" + kpId + "/followup-layers/L2/disable")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_LAYER_KEY_INVALID"));

        JsonNode detail = getJson(token, "/api/admin/kp/" + kpId);
        assertEquals(3, detail.get("followups").size());
        assertEquals("disabled", detail.get("followups").get(1).get("status").asText());
        assertEquals("active", detail.get("followups").get(2).get("status").asText());
    }

    @Test
    void structureValidationErrorsCarryFieldsDetail() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "校验簇" + suffix);

        Map<String, Object> badRubric = kpPayload(clusterId, "坏评分" + suffix);
        badRubric.put("rubric", List.of(group("L9", List.of(point(null, "basic", "引用不存在层")))));
        mvc.perform(post("/api/admin/kp").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(badRubric)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_STRUCTURE_INVALID"))
                .andExpect(jsonPath("$.fields['rubric[0].layerKey']").exists());

        Map<String, Object> tooManyVariants = kpPayload(clusterId, "多变体" + suffix);
        tooManyVariants.put("variants", List.of("一", "二", "三", "四", "五", "六"));
        mvc.perform(post("/api/admin/kp").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(tooManyVariants)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_STRUCTURE_INVALID"))
                .andExpect(jsonPath("$.fields.variants").exists());

        Map<String, Object> missingStopLayer = kpPayload(clusterId, "缺停止层" + suffix);
        missingStopLayer.put("levelReq", Map.of("senior", levelReq(true, null)));
        mvc.perform(post("/api/admin/kp").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(missingStopLayer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_STRUCTURE_INVALID"))
                .andExpect(jsonPath("$.fields['levelReq.senior.stopLayer']").exists());
    }

    @Test
    void prereqMountCycleWithArrowPathAndRetiredTargetRejected() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "前置簇" + suffix);
        long aId = createKp(token, kpPayload(clusterId, "前置A" + suffix));
        Map<String, Object> bPayload = kpPayload(clusterId, "前置B" + suffix);
        bPayload.put("prereqIds", List.of(aId));
        long bId = createKp(token, bPayload);
        Map<String, Object> cPayload = kpPayload(clusterId, "前置C" + suffix);
        cPayload.put("prereqIds", List.of(bId));
        long cId = createKp(token, cPayload);

        JsonNode cDetail = getJson(token, "/api/admin/kp/" + cId);
        assertEquals(1, cDetail.get("prereqIds").size());
        assertEquals(bId, cDetail.get("prereqIds").get(0).asLong());
        assertEquals("前置B" + suffix, cDetail.get("prereqList").get(0).get("title").asText());

        Map<String, Object> selfLoop = kpPayload(clusterId, "前置A" + suffix);
        selfLoop.put("prereqIds", List.of(aId));
        mvc.perform(put("/api/admin/kp/" + aId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(selfLoop)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_PREREQ_CYCLE"));

        Map<String, Object> backEdge = kpPayload(clusterId, "前置A" + suffix);
        backEdge.put("prereqIds", List.of(cId));
        mvc.perform(put("/api/admin/kp/" + aId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(backEdge)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_PREREQ_CYCLE"))
                .andExpect(jsonPath("$.message", containsString("→")));

        mvc.perform(post("/api/admin/kp/" + bId + "/retire").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("mode", "discard"))))
                .andExpect(status().isOk());
        Map<String, Object> retiredPrereq = kpPayload(clusterId, "前置A" + suffix);
        retiredPrereq.put("prereqIds", List.of(bId));
        mvc.perform(put("/api/admin/kp/" + aId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(retiredPrereq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_PREREQ_INVALID"));
    }

    @Test
    void statusMachineForwardOnlyAndActiveRevisionRefreshesReviewDue() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "状态簇" + suffix);
        long kpId = createKp(token, kpPayload(clusterId, "状态题" + suffix));
        long directId = createKp(token, kpPayload(clusterId, "直达题" + suffix));

        mvc.perform(post("/api/admin/kp/" + directId + "/status").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "active"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_STATUS_ILLEGAL"));

        mvc.perform(post("/api/admin/kp/" + kpId + "/status").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "reviewed"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/kp/" + kpId + "/status").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "active"))))
                .andExpect(status().isOk());

        Map<String, Object> edited = kpPayload(clusterId, "状态题（修订）" + suffix);
        mvc.perform(put("/api/admin/kp/" + kpId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(edited)))
                .andExpect(status().isOk());

        JsonNode detail = getJson(token, "/api/admin/kp/" + kpId);
        assertEquals("active", detail.get("status").asText());
        LocalDate last = LocalDate.parse(detail.get("lastReviewedAt").asText().substring(0, 10));
        LocalDate due = LocalDate.parse(detail.get("reviewDueAt").asText().substring(0, 10));
        assertEquals(90, ChronoUnit.DAYS.between(last, due));
    }

    @Test
    void retireDiscardAndMergeMovesVariantsWithDedup() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "下线簇" + suffix);

        Map<String, Object> source = kpPayload(clusterId, "源题" + suffix);
        source.put("variants", List.of("v1", "v2"));
        long sourceId = createKp(token, source);
        Map<String, Object> target = kpPayload(clusterId, "目标题" + suffix);
        target.put("variants", List.of("v2", "v3"));
        long targetId = createKp(token, target);
        long discardId = createKp(token, kpPayload(clusterId, "弃用题" + suffix));

        mvc.perform(post("/api/admin/kp/" + sourceId + "/retire").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("mode", "merge", "targetId", targetId))))
                .andExpect(status().isOk());

        JsonNode sourceDetail = getJson(token, "/api/admin/kp/" + sourceId);
        assertEquals("retired", sourceDetail.get("status").asText());
        assertEquals(targetId, sourceDetail.get("mergedInto").asLong());
        JsonNode targetDetail = getJson(token, "/api/admin/kp/" + targetId);
        assertEquals(3, targetDetail.get("variants").size());
        assertEquals("v2", targetDetail.get("variants").get(0).asText());
        assertEquals("v3", targetDetail.get("variants").get(1).asText());
        assertEquals("v1", targetDetail.get("variants").get(2).asText());

        mvc.perform(post("/api/admin/kp/" + targetId + "/retire").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("mode", "merge", "targetId", targetId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_MERGE_INVALID"));

        mvc.perform(post("/api/admin/kp/" + discardId + "/retire").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("mode", "discard"))))
                .andExpect(status().isOk());
        JsonNode discardDetail = getJson(token, "/api/admin/kp/" + discardId);
        assertEquals("retired", discardDetail.get("status").asText());
        assertTrue(discardDetail.get("mergedInto").isNull());

        mvc.perform(post("/api/admin/kp/" + discardId + "/status").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("status", "reviewed"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_STATUS_ILLEGAL"));
        mvc.perform(post("/api/admin/kp/" + discardId + "/retire").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("mode", "discard"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KB_STATUS_ILLEGAL"));
    }

    @Test
    void listFiltersCombinationsAndPaginationTotal() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "检索簇" + suffix);
        insertKp(clusterId, "HTTPS握手原理" + suffix, 3, "draft");
        long kp2 = insertKp(clusterId, "HTTPS证书链" + suffix, 2, "reviewed");
        long kp3 = insertKp(clusterId, "TCP挥手流程" + suffix, 3, "draft");
        insertKp(clusterId, "TCP握手细节" + suffix, 4, "active");
        insertKp(clusterId, "TCP握手进阶" + suffix, 1, "retired");
        jdbc.update("UPDATE kp_main SET baseline_levels = 'junior' WHERE id = ?", kp2);
        jdbc.update("UPDATE kp_main SET last_reviewed_at = '2019-10-01 00:00:00', "
                + "review_due_at = '2020-01-01 00:00:00' WHERE id = ?", kp3);
        assertEquals(5L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_main WHERE cluster_id = ?", Long.class, clusterId));

        // 共享测试库跨运行持久，全局计数会被历史数据污染——筛选断言一律限定在本簇
        assertEquals(2, total(token, "keyword=HTTPS&clusterId=" + clusterId));
        assertEquals(1, total(token, "keyword=握手&status=draft&clusterId=" + clusterId));
        assertEquals(2, total(token, "difficulty=3&clusterId=" + clusterId));
        assertEquals(1, total(token, "baseline=marked&clusterId=" + clusterId));
        assertEquals(4, total(token, "baseline=unmarked&clusterId=" + clusterId));
        assertEquals(1, total(token, "reviewDue=true&clusterId=" + clusterId));
        assertEquals(1, total(token, "status=retired&clusterId=" + clusterId));
        assertEquals(5, total(token, "clusterId=" + clusterId));
        assertEquals(5, total(token, "domainId=" + domainId));

        JsonNode page1 = getJson(token, "/api/admin/kp?size=2&page=1&clusterId=" + clusterId);
        assertEquals(5, page1.get("total").asLong());
        assertEquals(2, page1.get("list").size());
        JsonNode page3 = getJson(token, "/api/admin/kp?size=2&page=3&clusterId=" + clusterId);
        assertEquals(1, page3.get("list").size());

        JsonNode marked = getJson(token, "/api/admin/kp?baseline=marked&clusterId=" + clusterId);
        assertEquals("junior", marked.get("list").get(0).get("baselineLevels").asText());
        JsonNode row = marked.get("list").get(0);
        assertEquals("HTTPS证书链" + suffix, row.get("title").asText());
        assertEquals("检索簇" + suffix, row.get("clusterName").asText());
        assertEquals("测试域" + suffix, row.get("domainName").asText());
        assertTrue(row.has("lastReviewedAt"));
        assertTrue(row.has("reviewDueAt"));
    }

    @Test
    void freqSignalUpsertsInsteadOfInserting() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "频率簇" + suffix);
        long kpId = createKp(token, kpPayload(clusterId, "频率题" + suffix));

        mvc.perform(put("/api/admin/kp/" + kpId + "/freq-signal").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("signalLevel", "high", "note", "客户常问"))))
                .andExpect(status().isOk());

        JsonNode detail = getJson(token, "/api/admin/kp/" + kpId);
        assertEquals(1, detail.get("freqSignals").size());
        assertEquals("manual", detail.get("freqSignals").get(0).get("source").asText());
        assertEquals("high", detail.get("freqSignals").get(0).get("signalLevel").asText());
        assertEquals("客户常问", detail.get("freqSignals").get(0).get("note").asText());

        mvc.perform(put("/api/admin/kp/" + kpId + "/freq-signal").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("signalLevel", "low", "note", "改判低频"))))
                .andExpect(status().isOk());
        detail = getJson(token, "/api/admin/kp/" + kpId);
        assertEquals(1, detail.get("freqSignals").size());
        assertEquals("low", detail.get("freqSignals").get(0).get("signalLevel").asText());
    }

    @Test
    void metaCarriesDomainKpCountAndClusterList() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long cluster1 = createCluster(token, domainId, "簇一" + suffix);
        long cluster2 = createCluster(token, domainId, "簇二" + suffix);
        insertKp(cluster1, "元题一" + suffix, 2, "draft");
        insertKp(cluster1, "元题二" + suffix, 3, "retired");
        insertKp(cluster2, "元题三" + suffix, 4, "active");

        JsonNode meta = getJson(token, "/api/admin/kp/meta");
        JsonNode domainMeta = findById(meta.get("domains"), domainId);
        assertNotNull(domainMeta);
        assertEquals("测试域" + suffix, domainMeta.get("name").asText());
        assertEquals("active", domainMeta.get("status").asText());
        assertEquals(2, domainMeta.get("kpCount").asLong());

        JsonNode clusterMeta1 = findById(meta.get("clusters"), cluster1);
        assertNotNull(clusterMeta1);
        assertEquals("簇一" + suffix, clusterMeta1.get("name").asText());
        assertEquals(domainId, clusterMeta1.get("domainId").asLong());
        assertEquals("draft", clusterMeta1.get("status").asText());
        assertNotNull(findById(meta.get("clusters"), cluster2));
    }

    @Test
    void adminKpEndpointsRejectAnonymousAndCustomer() throws Exception {
        mvc.perform(get("/api/admin/kp"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/kp/meta"))
                .andExpect(status().isUnauthorized());

        String customerToken = customerToken();
        mvc.perform(get("/api/admin/kp").header(BEARER, "Bearer " + customerToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/kp").header(BEARER, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("title", "越权题"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void baselineLevelsPersistedFromStopLayersAndMarkedFilterHits() throws Exception {
        String token = staffToken();
        String suffix = String.valueOf(System.nanoTime());
        long domainId = insertDomain(suffix);
        long clusterId = createCluster(token, domainId, "基线簇" + suffix);

        Map<String, Object> payload = kpPayload(clusterId, "基线题" + suffix);
        payload.put("followups", List.of(layer("L1", "深入", "为什么", null, null, "active")));
        payload.put("rubric", List.of(group("main", List.of(point(null, "basic", "主干")))));
        payload.put("levelReq", Map.of(
                "junior", levelReq(true, "main"),
                "mid", levelReq(false, "L1")));
        long kpId = createKp(token, payload);

        assertEquals("junior,mid", jdbc.queryForObject(
                "SELECT baseline_levels FROM kp_main WHERE id = ?", String.class, kpId));
        assertEquals(1, total(token, "baseline=marked&clusterId=" + clusterId));

        Map<String, Object> edited = kpPayload(clusterId, "基线题" + suffix);
        edited.put("followups", payload.get("followups"));
        edited.put("rubric", payload.get("rubric"));
        edited.put("levelReq", Map.of("senior", levelReq(false, "main")));
        mvc.perform(put("/api/admin/kp/" + kpId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(edited)))
                .andExpect(status().isOk());
        assertEquals("senior", jdbc.queryForObject(
                "SELECT baseline_levels FROM kp_main WHERE id = ?", String.class, kpId));
        // 换了已标档位：marked（非空）依旧命中，unmarked 依旧不命中
        assertEquals(1, total(token, "baseline=marked&clusterId=" + clusterId));
        assertEquals(0, total(token, "baseline=unmarked&clusterId=" + clusterId));
    }

    private long insertDomain(String suffix) {
        String name = "测试域" + suffix;
        jdbc.update("INSERT INTO skill_domain (name, description, sort_order, status) VALUES (?, NULL, 0, 'active')",
                name);
        return jdbc.queryForObject("SELECT id FROM skill_domain WHERE name = ?", Long.class, name);
    }

    private long insertKp(long clusterId, String title, int difficulty, String status) {
        jdbc.update("INSERT INTO kp_main (cluster_id, sub_type, title, variants, followups, rubric, samples, "
                        + "level_req, baseline_levels, difficulty, status) "
                        + "VALUES (?, 'default', ?, '[]', '[]', '[]', '{}', '{}', '', ?, ?)",
                clusterId, title, difficulty, status);
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

    private long createKp(String token, Map<String, Object> payload) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/kp").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
    }

    private Map<String, Object> kpPayload(long clusterId, String title) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("clusterId", clusterId);
        payload.put("subType", "default");
        payload.put("title", title);
        payload.put("difficulty", 3);
        payload.put("variants", List.of());
        payload.put("followups", List.of());
        payload.put("rubric", List.of());
        payload.put("samples", Map.of());
        payload.put("levelReq", Map.of());
        payload.put("prereqIds", List.of());
        return payload;
    }

    private Map<String, Object> layer(String layerKey, String dir, String text, String expect,
            String stopCondition, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("layerKey", layerKey);
        m.put("dir", dir);
        m.put("text", text);
        m.put("expect", expect);
        m.put("stopCondition", stopCondition);
        m.put("status", status);
        return m;
    }

    private Map<String, Object> group(String layerKey, List<Map<String, Object>> points) {
        Map<String, Object> m = new HashMap<>();
        m.put("layerKey", layerKey);
        m.put("points", points);
        return m;
    }

    private Map<String, Object> point(String pointId, String level, String text) {
        Map<String, Object> m = new HashMap<>();
        m.put("pointId", pointId);
        m.put("level", level);
        m.put("text", text);
        return m;
    }

    private Map<String, Object> levelReq(boolean required, String stopLayer) {
        Map<String, Object> m = new HashMap<>();
        m.put("required", required);
        m.put("stopLayer", stopLayer);
        return m;
    }

    private int total(String token, String query) throws Exception {
        JsonNode node = getJson(token, "/api/admin/kp?" + query);
        return (int) node.get("total").asLong();
    }

    private JsonNode findById(JsonNode list, long id) {
        for (JsonNode node : list) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    private String staffToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        return field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
    }

    private String customerToken() throws Exception {
        String username = "kpc" + System.nanoTime();
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
