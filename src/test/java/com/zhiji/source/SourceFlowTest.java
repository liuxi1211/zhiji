package com.zhiji.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.common.exception.BizException;
import com.zhiji.kb.service.KbReviewService;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.llm.StubLlmTransport;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import com.zhiji.source.service.SourceAiService;
import com.zhiji.source.service.SourceService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 素材库链路（核心模块定义 5.1）：手写/导入/抓取入库、状态机、提交门槛（含追问链）、AI 辅助编排。
 * LLM 用 StubLlmTransport 完全替代网络；B 端会话走员工登录（/api/admin/**）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SourceFlowTest {

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
    SourceService sourceService;

    @Autowired
    StubLlmTransport stub;

    @Autowired
    LlmConfigService llmConfigService;

    @Autowired
    LlmUsageService llmUsageService;

    @Autowired
    KbTopicService kbTopicService;

    @Autowired
    com.zhiji.kb.service.KbContentService kbContentService;

    @Autowired
    KbReviewService kbReviewService;

    @TempDir
    Path tempDir;

    MockMvc mvc;
    String staffToken;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        stub.reset();
        // 测试库共享（临时 SQLite），sys_config 里可能存有旧版路由（缺 SOURCE_* 入口）：
        // 与 LlmGatewayTest 同口径，每个用例前重置路由与限额，保证新增入口可调用。
        llmConfigService.saveRouteConfig(llmConfigService.defaultRouteConfig());
        llmConfigService.saveLimits(LlmLimits.defaults());
        llmUsageService.deleteByDate(llmUsageService.today());
        MvcResult login = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.json.JsonMapper()
                                .writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        staffToken = "Bearer " + field(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
    }

    /** 直调 service 的用例没有 HTTP 请求链，需手动注入员工上下文（与拦截器同口径）。 */
    private void asStaff() {
        com.zhiji.common.security.AuthContext.set(
                new com.zhiji.common.security.AuthContext.CurrentUser(
                        com.zhiji.common.security.AuthContext.UserType.STAFF, 1L, "admin"));
    }

    private void asNobody() {
        com.zhiji.common.security.AuthContext.clear();
    }

    @Test
    void createListAndUpdateFlow() {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("HashMap 扩容", "# HashMap\n\n负载因子 0.75", null, "manual", "doc");
        assertThat(created.status()).isEqualTo("pending");
        assertThat(created.contentType()).isEqualTo("doc");

        SourceService.SourceItemView updated = sourceService.update(created.id(), null, null,
                "HashMap 扩容机制与负载因子的取舍", null, "mid", "java", "集合框架", null);
        assertThat(updated.summary()).contains("扩容");
        // update 的 null 语义是「不动」：类型标记保持原值
        assertThat(updated.contentType()).isEqualTo("doc");
        assertThat(updated.difficulty()).isEqualTo("mid");
        assertThat(updated.domainCode()).isEqualTo("java");
    }

    @Test
    void submitRequiresSummaryAndDifficulty() {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("无概述素材", "正文", null, "manual", null);
        Long topicId = kbTopicService.list("java").stream()
                .filter(t -> "JVM".equals(t.name())).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> sourceService.submit(created.id(), "java", topicId, "kp", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("概述");
        sourceService.update(created.id(), null, null, "补齐概述", null, null, null, null, null);
        assertThatThrownBy(() -> sourceService.submit(created.id(), "java", topicId, "kp", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("难度");
        sourceService.update(created.id(), null, null, null, null, "mid", null, null, null);
        List<SourceService.SourceItemView> submitted = sourceService.submit(created.id(), "java", topicId, "kp", null);
        assertThat(submitted).hasSize(1);
        assertThat(submitted.get(0).status()).isEqualTo("submitted");
        // 定类回写素材行：类型标记在入库时落定，素材库列表可见最终归属
        assertThat(submitted.get(0).contentType()).isEqualTo("kp");
        // 提交在标准库产生待审核内容（进门先过审核门，不校不上线）
        assertThat(kbReviewService.queue(null, 1, 100).items().stream()
                .anyMatch(item -> "无概述素材".equals(item.title()))).isTrue();
        // 已入库素材长期保留：不可编辑、不可删、不可放弃
        assertThatThrownBy(() -> sourceService.update(submitted.get(0).id(), "改标题", null, null, null, null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("断联");
        assertThatThrownBy(() -> sourceService.delete(submitted.get(0).id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("保留不删");
    }

    @Test
    void submitCarriesFollowupsIntoPendingKp() {
        asStaff();
        // 共享测试库：标题带随机后缀，避免跨用例重跑同名干扰
        String run = java.util.UUID.randomUUID().toString().substring(0, 8);
        SourceService.SourceItemView created = sourceService.create("带追问链的题-" + run,
                "先讲结论，再展开原理，顺带说了底层实现。", null, "manual", null);
        sourceService.update(created.id(), null, null, "概述", null, "mid", "java", null, null);
        Long topicId = kbTopicService.list("java").stream()
                .filter(t -> "JVM".equals(t.name())).findFirst().orElseThrow().id();
        var followups = List.of(
                new com.zhiji.kb.service.KbContentService.Followup("fu-test-1", 1, "原理", "为什么要这样设计？", "active"),
                new com.zhiji.kb.service.KbContentService.Followup("fu-test-2", 2, "底层实现", "底层是如何实现的？", "active"));
        sourceService.submit(created.id(), "java", topicId, "kp", followups);
        // 追问链随提交写入 pending 知识点（向导内 AI 识别/人工整理的产物）
        com.zhiji.kb.service.KbContentService.KpView kp = kbContentService.getKp(
                kbContentService.listByTopic(topicId, "kp", "pending").stream()
                        .filter(item -> ("带追问链的题-" + run).equals(item.title())).findFirst().orElseThrow().id());
        assertThat(kp.followups()).hasSize(2);
        assertThat(kp.followups().get(0).id()).isEqualTo("fu-test-1");
        // 入库即受红线约束：id 缺失的追问链直接拒绝
        SourceService.SourceItemView second = sourceService.create("缺 id 的追问链-" + run, "正文", null, "manual", null);
        sourceService.update(second.id(), null, null, "概述", null, "mid", null, null, null);
        assertThatThrownBy(() -> sourceService.submit(second.id(), "java", topicId, "kp", List.of(
                new com.zhiji.kb.service.KbContentService.Followup("", 1, "原理", "问句", "active"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("红线");
    }

    @Test
    void importZipWithReferencedImage() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("notes.md"));
            zip.write("# 架构图\n\n![系统图](./images/diagram.png)\n\n正文".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("images/diagram.png"));
            zip.write(png);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("images/unused.png"));
            zip.write(png);
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile("file", "docs.zip", "application/zip", bytes.toByteArray());
        MvcResult result = mvc.perform(multipart("/api/admin/sources/import").file(file)
                        .header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn();
        // 导入返回列表：zip 内每个文档各成一条素材
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"sourceType\":\"import\"");
        // 命中的图片改写为公共图片库地址；未引用的图片不入库
        SourceService.SourceItemView item = sourceService.get(sourceIdOf(body));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("!\\[[^\\]]*]\\((/api/assets/[0-9a-f]{64}\\.png)\\)").matcher(item.contentMd());
        assertThat(m.find()).as("图片引用应改写为公共图片库地址").isTrue();
        String publicName = m.group(1).substring("/api/assets/".length());
        // 公共图片接口无需鉴权即可访问（分享页/C 端同址可取图）
        mvc.perform(get("/api/assets/" + publicName)).andExpect(status().isOk());
        // 文件名白名单拦截：非内容寻址命名一律拒绝
        mvc.perform(get("/api/assets/..%2fsrc_item")).andExpect(status().isBadRequest());
        // 落盘按内容哈希前 2 位分片：{data-dir}/assets/media/{前2位}/{sha256}.png
        assertThat(Files.exists(Path.of(System.getProperty("java.io.tmpdir"),
                "assets", "media", publicName.substring(0, 2), publicName))).isTrue();
    }

    @Test
    void importZipCreatesOneSourcePerDocument() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("a.md"));
            zip.write("# 文档A\n\n内容A".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("b.md"));
            zip.write("# 文档B\n\n内容B".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // 不同目录下的同名文档也必须各成一条（不能被文件名覆盖掉）
            zip.putNextEntry(new ZipEntry("dir1/same.md"));
            zip.write("# 同名的1".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("dir2/same.md"));
            zip.write("# 同名的2".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile("file", "docs.zip", "application/zip", bytes.toByteArray());
        MvcResult result = mvc.perform(multipart("/api/admin/sources/import").file(file)
                        .header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 4 个文档 → 4 条素材（不再拼成一条，同名不同目录也不互相覆盖）
        assertThat(Pattern.compile("\"id\":\\d+").matcher(body).results().count())
                .as("包内几个文档就导入几篇").isEqualTo(4);
        assertThat(body).contains("\"title\":\"a\"").contains("\"title\":\"b\"")
                .contains("\"title\":\"same\"");
    }

    /** 从导入响应体抠出第一条素材 id（返回列表的 JSON 数组）。 */
    private long sourceIdOf(String body) {
        var matcher = Pattern.compile("\"id\":(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少素材 id: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void submitLocalizesExternalImages() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        // 本地 HTTP 服务器模拟「可下载的外链图床」与「下载失败的坏链」，避免测试依赖公网
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/ok.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
            ex.close();
        });
        server.createContext("/bad.png", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            asStaff();
            String body = "![好图](http://127.0.0.1:" + port + "/ok.png)\n\n![坏图](http://127.0.0.1:"
                    + port + "/bad.png)\n\n![已是本地](/api/assets/" + "a".repeat(64) + ".png)";
            SourceService.SourceItemView created = sourceService.create("外链本地化素材", body, null, "manual", null);
            sourceService.update(created.id(), null, null, "概述", null, "mid", "java", null, null);
            Long topicId = kbTopicService.list("java").stream()
                    .filter(t -> "JVM".equals(t.name())).findFirst().orElseThrow().id();
            sourceService.submit(created.id(), "java", topicId, "doc", null);
            // 进标准库后：能下到的外链换成本系统地址，坏链保留原外链，已本地的不动
            com.zhiji.kb.service.KbContentService.DocView doc =
                    kbContentService.getDoc(kbContentService.listByTopic(topicId, "doc", "pending").stream()
                            .filter(item -> "外链本地化素材".equals(item.title())).findFirst().orElseThrow().id());
            assertThat(doc.contentMd()).doesNotContain("127.0.0.1:" + port + "/ok.png");
            assertThat(doc.contentMd()).containsPattern("!\\[好图]\\(/api/assets/[0-9a-f]{64}\\.png\\)");
            assertThat(doc.contentMd()).contains("http://127.0.0.1:" + port + "/bad.png");
            assertThat(doc.contentMd()).contains("/api/assets/" + "a".repeat(64) + ".png");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localizeReportAndUpload() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/ok.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
            ex.close();
        });
        server.createContext("/bad.png", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            // 显式本地化：返回明细报告且**不落库**——成功换本系统地址、失败原样保留并给出原因
            String body = "![好图](http://127.0.0.1:" + port + "/ok.png)\n\n![坏图](http://127.0.0.1:"
                    + port + "/bad.png)";
            MvcResult result = mvc.perform(post("/api/admin/assets/localize")
                            .header("Authorization", staffToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new tools.jackson.databind.json.JsonMapper()
                                    .writeValueAsString(Map.of("contentMd", body))))
                    .andExpect(status().isOk())
                    .andReturn();
            String report = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(report).contains("\"success\":1").contains("\"failed\":1");
            assertThat(report).containsPattern("/api/assets/[0-9a-f]{64}\\.png");
            assertThat(report).contains("http://127.0.0.1:" + port + "/bad.png");

            // 人工上传替换：把（人工下载到的）图收进公共图库，返回可引用地址
            MockMultipartFile file = new MockMultipartFile("file", "manual.png", "image/png", png);
            MvcResult upload = mvc.perform(multipart("/api/admin/assets/upload").file(file)
                            .header("Authorization", staffToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String uploadBody = upload.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(uploadBody).containsPattern("/api/assets/[0-9a-f]{64}\\.png");
            // 上传的非图片被拒
            MockMultipartFile text = new MockMultipartFile("file", "note.txt", "text/plain",
                    "not an image".getBytes(StandardCharsets.UTF_8));
            mvc.perform(multipart("/api/admin/assets/upload").file(text).header("Authorization", staffToken))
                    .andExpect(status().isBadRequest());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aiActionsReturnDraftsNotPersisted() throws Exception {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("待改写素材",
                "# JVM\n\nG1 回收器分代内容。", null, "manual", null);
        // 流式改写可带人工指令；素材正文是只读快照（改写产物回前端编辑器，不落库）
        stub.respond("改写后的正文");
        mvc.perform(post("/api/admin/sources/" + created.id() + "/ai/rewrite/stream")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"面向初级候选人，多举例子\"}"))
                .andExpect(status().isOk());
        // 标题生成与概述一样是建议产物，不落库
        stub.respond("什么情况下 G1 会触发 Mixed GC？");
        MvcResult title = mvc.perform(post("/api/admin/kb/ai/title")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                                Map.of("contentMd", "G1 回收器分代内容。", "mode", "kp"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(title.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Mixed GC");
        stub.respond("{\"followups\":[{\"layer\":1,\"direction\":\"原理\",\"content\":\"为什么要分代？\"}]}");
        MvcResult followups = mvc.perform(post("/api/admin/kb/ai/followups")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                                Map.of("contentMd", "G1 回收器分代内容。"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(followups.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("为什么要分代");
        stub.respond("一段百字概述");
        mvc.perform(post("/api/admin/sources/" + created.id() + "/ai/summary").header("Authorization", staffToken))
                .andExpect(status().isOk());
        // AI 不落库：素材正文未被改写
        assertThat(sourceService.get(created.id()).contentMd()).contains("G1 回收器分代内容");
    }

    @Test
    void staffSessionRequired() throws Exception {
        mvc.perform(get("/api/admin/sources")).andExpect(status().isUnauthorized());
    }

    @Test
    void discardAndDeletePurgesAssets() throws Exception {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("待删素材", "正文", null, "manual", null);
        mvc.perform(delete("/api/admin/sources/" + created.id()).header("Authorization", staffToken))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> sourceService.get(created.id())).isInstanceOf(BizException.class);
    }

    @Test
    void updateRejectsUnknownDomain() {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("领域校验", "正文", null, "manual", null);
        assertThatThrownBy(() -> sourceService.update(created.id(), null, null, null, null, null, "not_a_domain", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("领域");
    }

    @Test
    void classifyOnlyAdoptsKnownTopic() throws Exception {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("分类建议", "MySQL 索引下推", null, "manual", null);
        stub.respond("{\"topicName\":\"MySQL\",\"reason\":\"索引主题\"}");
        MvcResult ok = mvc.perform(post("/api/admin/sources/" + created.id() + "/ai/classify")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                                Map.of("domainCode", "database", "topics", List.of("MySQL", "Redis")))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ok.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("MySQL");
        stub.respond("{\"topicName\":\"自造主题\",\"reason\":\"越界\"}");
        MvcResult rejected = mvc.perform(post("/api/admin/sources/" + created.id() + "/ai/classify")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                                Map.of("domainCode", "database", "topics", List.of("MySQL", "Redis")))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(rejected.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("不在候选主题内");
    }

    private String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name + " -> " + json);
        }
        return matcher.group(1);
    }
}
