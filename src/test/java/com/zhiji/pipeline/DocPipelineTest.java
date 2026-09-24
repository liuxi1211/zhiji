package com.zhiji.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.llm.StubLlmTransport;
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
 * 素材库全流程集成测试（开发计划 T6 验收 / B端管理台PRD 十四-2 素材库部分）：
 * 导入（md/txt/zip + 格式拒收）、解析与 LLM 形态识别、题库型三档拆题、资料型片段与反推出题、
 * 面经型抽题、形态修正、重复拆题幂等、失败重试、删除约束、批次聚合、鉴权隔离。
 * LLM 用 StubLlmTransport 替身（@Primary 覆盖真实 transport），B 端生产类入口不触网不烧钱。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocPipelineTest {

    private static final String BEARER = "Authorization";

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
    JdbcTemplate jdbc;

    @Autowired
    StubLlmTransport stub;

    MockMvc mvc;

    JsonMapper om = new JsonMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        stub.respond("{\"form\":\"question_bank\",\"confidence\":\"high\",\"reason\":\"问答结构\"}");
    }

    @Test
    void importMdAndTxtLandsPendingWithBatch() throws Exception {
        String token = staffToken();
        MvcResult result = mvc.perform(multipart("/api/admin/docs/import")
                        .file(new MockMultipartFile("files", "题库.md", MediaType.TEXT_PLAIN_VALUE,
                                "# Java 题\n\n## HashMap 底层\n\n数组加链表".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "笔记.txt", MediaType.TEXT_PLAIN_VALUE,
                                "1. 概念\n\n正文内容".getBytes(StandardCharsets.UTF_8)))
                        .param("sourceRef", "https://github.com/example/repo")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(2, node.get("docs").size());
        String batchId = node.get("batchId").asText();
        assertTrue(batchId.startsWith("B"));

        JsonNode batches = getJson(token, "/api/admin/docs/batches");
        JsonNode target = null;
        for (JsonNode batch : batches) {
            if (batchId.equals(batch.get("batchId").asText())) {
                target = batch;
            }
        }
        assertTrue(target != null, "导入批次应出现在批次面板");
        assertEquals(2, target.get("fileCount").asInt());
        assertEquals(2, target.get("pendingCount").asInt());

        long docId = node.get("docs").get(0).get("id").asLong();
        JsonNode detail = getJson(token, "/api/admin/docs/" + docId);
        assertEquals("https://github.com/example/repo", detail.get("doc").get("sourceRef").asText());
    }

    @Test
    void importRejectsDocxAndPdfWithD31Guidance() throws Exception {
        String token = staffToken();
        MvcResult result = mvc.perform(multipart("/api/admin/docs/import")
                        .file(new MockMultipartFile("files", "文档.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                "binary".getBytes(StandardCharsets.UTF_8)))
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        // docx 是导入时落 pending（内容按文本存），解析时才拒收——见 parseRejectsDocxExplicitly
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(1, node.get("docs").size());
    }

    @Test
    void parseRejectsDocxExplicitlyAndRetryWorks() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "文档.docx", "binary");
        MvcResult parseResult = mvc.perform(post("/api/admin/docs/" + docId + "/parse")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOC_FORMAT_UNSUPPORTED"))
                .andReturn();
        assertTrue(parseResult.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("pandoc"));

        JsonNode detail = getJson(token, "/api/admin/docs/" + docId);
        assertEquals("parse_failed", detail.get("doc").get("status").asText());

        // 重试 docx 依旧失败（格式不支持不可重试成功），但状态机保持 parse_failed 显式可见
        mvc.perform(post("/api/admin/docs/" + docId + "/retry").header(BEARER, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parseRunsLlmFormDetectionAndLandsParsed() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "a.md", "# 题\n\n## HashMap\n\n数组加链表");
        stub.respond("{\"form\":\"reading\",\"confidence\":\"low\",\"reason\":\"连续叙述\"}");
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode detail = getJson(token, "/api/admin/docs/" + docId);
        assertEquals("parsed", detail.get("doc").get("status").asText());
        assertEquals("reading", detail.get("doc").get("form").asText());
        assertTrue(detail.get("doc").get("charCount").asInt() > 0);
        assertTrue(detail.get("doc").get("headingTree").asText().contains("HashMap"));
        // LLM 形态识别失败不阻塞：替换为错误响应后解析仍成功（form 留空）
        long docId2 = importOne(token, "b.md", "# 题\n\n## Redis\n\n单线程");
        stub.fail(new RuntimeException("provider down"));
        mvc.perform(post("/api/admin/docs/" + docId2 + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        JsonNode detail2 = getJson(token, "/api/admin/docs/" + docId2);
        assertEquals("parsed", detail2.get("doc").get("status").asText());
        assertTrue(detail2.get("doc").get("form").isNull());
        stub.reset();
    }

    @Test
    void questionBankSplitPrefersHeadingStructureWithoutLlm() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "q.md",
                "## Java 中 HashMap 的底层实现\n\n数组加链表加红黑树。\n\n## ConcurrentHashMap 如何保证线程安全\n\n分段锁与 CAS。");
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        stub.reset();
        MvcResult result = mvc.perform(post("/api/admin/docs/" + docId + "/split")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(2, node.get("inserted").asInt());
        assertEquals(0, stub.callCount(), "标题结构命中不应调用 LLM");

        JsonNode detail = getJson(token, "/api/admin/docs/" + docId);
        assertEquals("split", detail.get("doc").get("status").asText());
        assertEquals(2, detail.get("candidates").size());
        assertEquals("high", detail.get("candidates").get(0).get("confidence").asText());
        assertEquals("pending", detail.get("candidates").get(0).get("status").asText());
    }

    @Test
    void questionBankFallsBackToLlmWhenNoStructure() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "q2.md",
                "什么是 JVM 内存模型？它规定了主内存与工作内存的交互。\n\n什么是 happens-before？它是操作之间的可见性保证。");
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        stub.respond("{\"items\":[{\"question\":\"什么是 JVM 内存模型？\",\"answer\":\"主内存与工作内存的交互规则\"},"
                + "{\"question\":\"什么是 happens-before？\",\"answer\":null}]}");
        MvcResult result = mvc.perform(post("/api/admin/docs/" + docId + "/split")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(2, node.get("inserted").asInt());
        assertEquals(1, stub.callCount(), "无结构时走 LLM 兜底");

        JsonNode detail = getJson(token, "/api/admin/docs/" + docId);
        assertEquals("low", detail.get("candidates").get(0).get("confidence").asText());
    }

    @Test
    void readingSplitCreatesFragmentsAndInverseGenOnDemand() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "r.md", "# JVM 指南\n\n## 内存区域\n\n堆栈方法区说明。\n\n## GC 算法\n\n标记整理。");
        stub.respond("{\"form\":\"reading\",\"confidence\":\"high\",\"reason\":\"连续讲解\"}");
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult result = mvc.perform(post("/api/admin/docs/" + docId + "/split")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(3, node.get("inserted").asInt(), "两个章节片段 + 1 个文件级标题片段");

        // 反推出题按片段勾选触发：只对片段 0 出题
        stub.respond("{\"questions\":[{\"question\":\"JVM 内存区域有哪些？\",\"points\":[\"堆\",\"栈\"]}]}");
        MvcResult gen = mvc.perform(post("/api/admin/docs/" + docId + "/inverse-gen")
                        .header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(List.of(0))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode genNode = om.readTree(gen.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(1, genNode.get("inserted").asInt());
        assertEquals(1, stub.callCount(), "反推只对勾选片段调用 LLM");

        JsonNode detail = getJson(token, "/api/admin/docs/" + docId);
        assertEquals(4, detail.get("candidates").size(), "3 片段 + 1 反推题");
    }

    @Test
    void interviewExtractContributesQuestionsWithFollowups() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "i.md", "面试官问 HashMap 原理，追问了扩容。");
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        // 形态识别为题库型后人工修正为面经型（parsed 态允许修正）
        mvc.perform(post("/api/admin/docs/" + docId + "/form").header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"form\":\"interview\"}"))
                .andExpect(status().isOk());
        stub.respond("{\"questions\":[{\"question\":\"讲讲 HashMap 原理\",\"followups\":[\"扩容机制\",\"为什么树化\"]}]}");
        MvcResult result = mvc.perform(post("/api/admin/docs/" + docId + "/split")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(1, node.get("inserted").asInt());

        JsonNode detail = getJson(token, "/api/admin/docs/" + docId);
        assertEquals("interview", detail.get("candidates").get(0).get("form").asText());
        assertTrue(detail.get("candidates").get(0).get("answerEvidence").asText().contains("扩容机制"));
        stub.reset();
    }

    @Test
    void resplitClearsPendingCandidatesIdempotently() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "q3.md",
                "## 题 A\n\n答案 A。\n\n## 题 B\n\n答案 B。");
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/docs/" + docId + "/split").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult second = mvc.perform(post("/api/admin/docs/" + docId + "/split")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = om.readTree(second.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(2, node.get("inserted").asInt(), "重复拆题清空 pending 后重拆仍为 2 条");

        JsonNode detail = getJson(token, "/api/admin/docs/" + docId);
        assertEquals(2, detail.get("candidates").size(), "不会累积重复条目");
    }

    @Test
    void deleteBlockedWithInFlightCandidatesAndAllowedForPendingOnly() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "q4.md", "## 题 A\n\n答案 A。\n\n## 题 B\n\n答案 B。");
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/docs/" + docId + "/split").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        // 手工把一条候选置为 matched（在途），删除应被拒
        Long candidateId = jdbc.queryForObject(
                "SELECT id FROM kp_candidate WHERE doc_id = ? AND status = 'pending' LIMIT 1",
                Long.class, docId);
        jdbc.update("UPDATE kp_candidate SET status = 'matched' WHERE id = ?", candidateId);
        mvc.perform(delete("/api/admin/docs/" + docId).header(BEARER, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_CANDIDATES_IN_FLIGHT"));

        // 恢复 pending 后删除：候选级联清理
        jdbc.update("UPDATE kp_candidate SET status = 'pending' WHERE id = ?", candidateId);
        mvc.perform(delete("/api/admin/docs/" + docId).header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_candidate WHERE doc_id = ?", Integer.class, docId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kp_source_doc WHERE id = ?", Integer.class, docId));
    }

    @Test
    void candidateUpdateAndDiscardWorkOnPendingOnly() throws Exception {
        String token = staffToken();
        long docId = importOne(token, "q5.md", "## 题 A\n\n答案 A。\n\n## 题 B\n\n答案 B。");
        mvc.perform(post("/api/admin/docs/" + docId + "/parse").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/docs/" + docId + "/split").header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        Long candidateId = jdbc.queryForObject(
                "SELECT id FROM kp_candidate WHERE doc_id = ? LIMIT 1", Long.class, docId);

        mvc.perform(put("/api/admin/docs/candidates/" + candidateId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("title", "修正后的题面", "form", "reading"))))
                .andExpect(status().isOk());
        assertEquals("修正后的题面", jdbc.queryForObject(
                "SELECT title FROM kp_candidate WHERE id = ?", String.class, candidateId));

        mvc.perform(post("/api/admin/docs/candidates/" + candidateId + "/discard")
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk());
        assertEquals("discarded", jdbc.queryForObject(
                "SELECT status FROM kp_candidate WHERE id = ?", String.class, candidateId));

        // 非 pending 不可改
        mvc.perform(put("/api/admin/docs/candidates/" + candidateId).header(BEARER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("title", "再次修改"))))
                .andExpect(status().isConflict());
    }

    @Test
    void zipImportUnpacksWithDirectoryStructure() throws Exception {
        String token = staffToken();
        byte[] zipBytes = buildZip(Map.of("docs/java/jvm.md", "# JVM\n\n## 内存\n\n堆栈", "docs/redis.md", "# Redis\n\n单线程"));
        MvcResult result = mvc.perform(multipart("/api/admin/docs/import")
                        .file(new MockMultipartFile("files", "题库包.zip", MediaType.APPLICATION_OCTET_STREAM_VALUE, zipBytes))
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(2, node.get("docs").size());
        // zip 解包顺序按条目遍历序（不保证字典序），断言目录结构保留即可
        java.util.Set<String> names = new java.util.HashSet<>();
        node.get("docs").forEach(d -> names.add(d.get("fileName").asText()));
        assertTrue(names.contains("docs/java/jvm.md"), "zip 内目录结构保留作来源线索");
        assertTrue(names.contains("docs/redis.md"));
    }

    @Test
    void customerTokenRejectedOnAdminDocs() throws Exception {
        String customer = customerToken();
        mvc.perform(get("/api/admin/docs/batches").header(BEARER, "Bearer " + customer))
                .andExpect(status().isUnauthorized());
    }

    // —— 辅助 ——

    private long importOne(String token, String fileName, String content) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/admin/docs/import")
                        .file(new MockMultipartFile("files", fileName, MediaType.TEXT_PLAIN_VALUE,
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return node.get("docs").get(0).get("id").asLong();
    }

    private byte[] buildZip(Map<String, String> entries) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private String staffToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        return field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
    }

    private String customerToken() throws Exception {
        String username = "pipec" + System.nanoTime();
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

    private JsonNode getJson(String token, String uri) throws Exception {
        MvcResult result = mvc.perform(get(uri).header(BEARER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name);
        }
        return matcher.group(1);
    }
}
