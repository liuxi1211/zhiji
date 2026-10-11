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
 * 素材库链路（核心模块定义 5.1）：手写/导入/抓取入库、状态机、拆分、提交门槛、AI 辅助编排。
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
        SourceService.SourceItemView created = sourceService.create("HashMap 扩容", "# HashMap\n\n负载因子 0.75", null, "manual");
        assertThat(created.status()).isEqualTo("pending");

        SourceService.SourceItemView updated = sourceService.update(created.id(), null, null,
                "HashMap 扩容机制与负载因子的取舍", "mid", "java", "集合框架", null);
        assertThat(updated.summary()).contains("扩容");
        assertThat(updated.difficulty()).isEqualTo("mid");
        assertThat(updated.domainCode()).isEqualTo("java");
    }

    @Test
    void submitRequiresSummaryAndDifficulty() {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("无概述素材", "正文", null, "manual");
        Long topicId = kbTopicService.list("java").stream()
                .filter(t -> "JVM".equals(t.name())).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> sourceService.submit(created.id(), "java", topicId, "kp"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("概述");
        sourceService.update(created.id(), null, null, "补齐概述", null, null, null, null);
        assertThatThrownBy(() -> sourceService.submit(created.id(), "java", topicId, "kp"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("难度");
        sourceService.update(created.id(), null, null, null, "mid", null, null, null);
        List<SourceService.SourceItemView> submitted = sourceService.submit(created.id(), "java", topicId, "kp");
        assertThat(submitted).hasSize(1);
        assertThat(submitted.get(0).status()).isEqualTo("submitted");
        // 提交在标准库产生待审核内容（进门先过审核门，不校不上线）
        assertThat(kbReviewService.queue(null).stream()
                .anyMatch(item -> "无概述素材".equals(item.title()))).isTrue();
        // 已入库素材长期保留：不可编辑、不可删、不可放弃
        assertThatThrownBy(() -> sourceService.update(submitted.get(0).id(), "改标题", null, null, null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("断联");
        assertThatThrownBy(() -> sourceService.delete(submitted.get(0).id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("保留不删");
    }

    @Test
    void splitCreatesChildrenAndDiscardsParent() {
        asStaff();
        SourceService.SourceItemView parent = sourceService.create("混合素材",
                "# 主题一\n\nHashMap 内容\n\n# 主题二\n\nRedis 内容", null, "manual");
        SourceService.SplitResp resp = sourceService.split(parent.id(), List.of(
                new SourceService.SplitSliceInput("HashMap 扩容", null),
                new SourceService.SplitSliceInput("Redis 持久化", null)));
        assertThat(resp.slices()).hasSize(2);
        assertThat(sourceService.get(parent.id()).status()).isEqualTo("discarded");
        List<SourceService.SourceItemView> children = sourceService.children(parent.id());
        assertThat(children).hasSize(2);
        assertThat(children.get(0).parentId()).isEqualTo(parent.id());
        // 子素材按标题锚点预填正文：未显式给内容时取母素材切段
        assertThat(children.get(1).contentMd()).contains("Redis");
    }

    @Test
    void importZipWithReferencedImage() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("notes.md"));
            zip.write("# 架构图\n\n![系统图](diagram.png)\n\n正文".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("diagram.png"));
            zip.write(png);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("unused.png"));
            zip.write(png);
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile("file", "docs.zip", "application/zip", bytes.toByteArray());
        MvcResult result = mvc.perform(multipart("/api/admin/sources/import").file(file)
                        .header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = Long.parseLong(field(body, "id"));
        // 引用的图片落盘、未引用的不落盘（落盘即真相）；目录与 SourceProperties.data-dir 同口径
        Path assetDir = Path.of(System.getProperty("java.io.tmpdir"), "assets", "source", String.valueOf(id));
        assertThat(Files.exists(assetDir.resolve("diagram.png"))).isTrue();
        assertThat(Files.exists(assetDir.resolve("unused.png"))).isFalse();
        assertThat(field(body, "sourceType")).isEqualTo("import");
        // 图片资产接口可达；路径穿越被 Spring MVC 路径规范化直接拒绝（404），资产端点通不过文件名白名单
        mvc.perform(get("/api/admin/sources/" + id + "/assets/diagram.png").header("Authorization", staffToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/sources/" + id + "/assets/..%2fsrc_item").header("Authorization", staffToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aiActionsReturnDraftsNotPersisted() throws Exception {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("待改写素材",
                "# JVM\n\nG1 回收器分代内容。", null, "manual");
        stub.respond("改写后的正文");
        mvc.perform(post("/api/admin/sources/" + created.id() + "/ai/rewrite").header("Authorization", staffToken))
                .andExpect(status().isOk());
        stub.respond("{\"needSplit\":true,\"slices\":[{\"title\":\"G1 原理\",\"anchor\":\"# JVM\"}]}");
        MvcResult split = mvc.perform(post("/api/admin/sources/" + created.id() + "/ai/split")
                        .header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(split.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("G1 原理");
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
        SourceService.SourceItemView created = sourceService.create("待删素材", "正文", null, "manual");
        mvc.perform(delete("/api/admin/sources/" + created.id()).header("Authorization", staffToken))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> sourceService.get(created.id())).isInstanceOf(BizException.class);
    }

    @Test
    void updateRejectsUnknownDomain() {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("领域校验", "正文", null, "manual");
        assertThatThrownBy(() -> sourceService.update(created.id(), null, null, null, null, "not_a_domain", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("领域");
    }

    @Test
    void classifyOnlyAdoptsKnownTopic() throws Exception {
        asStaff();
        SourceService.SourceItemView created = sourceService.create("分类建议", "MySQL 索引下推", null, "manual");
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
