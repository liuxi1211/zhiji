package com.zhiji.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.AnswerSample;
import com.zhiji.kb.service.KbContentService.ContentItemView;
import com.zhiji.kb.service.KbContentService.Followup;
import com.zhiji.kb.service.KbContentService.KpView;
import com.zhiji.kb.service.KbContentService.LevelRule;
import com.zhiji.kb.service.KbContentService.UpdateDocReq;
import com.zhiji.kb.service.KbContentService.UpdateKpReq;
import com.zhiji.kb.service.KbContentService.Variant;
import com.zhiji.kb.service.KbMergeService;
import com.zhiji.kb.service.KbMergeService.AiFollowup;
import com.zhiji.kb.service.KbMergeService.AiScoringPoint;
import com.zhiji.kb.service.KbMergeService.MergeResp;
import com.zhiji.kb.service.KbReviewService;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.llm.StubLlmTransport;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import com.zhiji.source.service.SourceService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 标准知识库链路（核心模块定义 5.2）：主题组织、直提/向导提交进门、审核四项校验门、
 * 同位合并（含 AI 一键合并）、复核到期与送复审、下线红线、追问层身份永久、模糊搜索。
 * LLM 用 StubLlmTransport 完全替代网络；B 端会话走员工登录（/api/admin/**）。
 */
@SpringBootTest
@ActiveProfiles("test")
class KbFlowTest {

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
    KbTopicService topicService;

    @Autowired
    KbContentService contentService;

    @Autowired
    KbReviewService reviewService;

    @Autowired
    KbMergeService mergeService;

    @Autowired
    SourceService sourceService;

    @Autowired
    StubLlmTransport stub;

    @Autowired
    LlmConfigService llmConfigService;

    @Autowired
    LlmUsageService llmUsageService;

    MockMvc mvc;
    String staffToken;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        stub.reset();
        llmConfigService.saveRouteConfig(llmConfigService.defaultRouteConfig());
        llmConfigService.saveLimits(LlmLimits.defaults());
        llmUsageService.deleteByDate(llmUsageService.today());
        MvcResult login = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.json.JsonMapper()
                                .writeValueAsString(java.util.Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        staffToken = "Bearer " + field(login.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "token");
    }

    private void asStaff() {
        AuthContext.set(new AuthContext.CurrentUser(AuthContext.UserType.STAFF, 1L, "admin"));
    }

    private void asNobody() {
        AuthContext.clear();
    }

    /** 共享测试库重跑兜底：主题已存在时直接取回。 */
    private long freshTopic(String name) {
        return freshTopic("java", name);
    }

    private long freshTopic(String domainCode, String name) {
        try {
            return topicService.create(domainCode, name).id();
        } catch (BizException e) {
            return topicService.list(domainCode).stream()
                    .filter(t -> name.equals(t.name()))
                    .findFirst().orElseThrow().id();
        }
    }

    private long createKp(long topicId, String title, String summary) {
        return contentService.createPending("kp", "java", topicId, title, summary, "正文：" + title, "mid",
                null, "wizard", null);
    }

    private long createDoc(long topicId, String title) {
        return contentService.createPending("doc", "java", topicId, title, "摘要", "正文：" + title, "mid",
                "https://example.com/article", "direct", null);
    }

    private void passKp(long id) {
        reviewService.pass("kp", id,
                new KbReviewService.ReviewChecks("pass", "pass", "pass", "pass", null), null, null);
    }

    @Test
    void topicSeedAndRename() {
        asStaff();
        // 本用例会改名/合并主题，共享测试库重跑需唯一名（每次运行加随机后缀）
        String run = java.util.UUID.randomUUID().toString().substring(0, 8);
        // KbBootstrap 种子：DomainTopic 常量组已落表
        assertThat(topicService.list("java").stream().anyMatch(t -> "JVM".equals(t.name()))).isTrue();
        long topicA = topicService.create("java", "目录主题A-" + run).id();
        KbTopicService.TopicView renamed = topicService.rename(topicA, "目录主题A改名-" + run);
        assertThat(renamed.name()).isEqualTo("目录主题A改名-" + run);
        long duplicate = topicService.create("java", "目录主题B-" + run).id();
        assertThatThrownBy(() -> topicService.rename(duplicate, "目录主题A改名-" + run))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("同名");
        // 合并主题只允许同领域
        long crossDomain = topicService.create("database", "跨域主题X-" + run).id();
        assertThatThrownBy(() -> topicService.merge(topicA, crossDomain))
                .hasMessageContaining("同一领域");
        // 合并主题：内容迁移、原主题封存
        long sourceTopic = topicService.create("java", "合并源主题-" + run).id();
        long migrated = createKp(sourceTopic, "被迁移题-" + run, "摘要");
        long targetTopic = topicService.create("java", "合并目标主题-" + run).id();
        topicService.merge(sourceTopic, targetTopic);
        assertThat(contentService.listByTopic(targetTopic, "kp", null))
                .anyMatch(item -> item.id() == migrated);
        assertThatThrownBy(() -> topicService.rename(sourceTopic, "再改名-" + run))
                .hasMessageContaining("已合并");
        asNobody();
    }

    @Test
    void directSubmitCreatesPending() {
        asStaff();
        long topicId = freshTopic("直提主题");
        // 直达新建同样受概述门槛（D7）
        assertThatThrownBy(() -> contentService.createPending("kp", "java", topicId, "无概述题",
                null, "正文", "mid", null, "direct", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("概述");
        long kpId = contentService.createPending("kp", "java", topicId, "直提题面", "概述", "正文", "mid",
                null, "direct", null);
        KpView kp = contentService.getKp(kpId);
        assertThat(kp.status()).isEqualTo("pending");
        assertThat(kp.createdBy()).isEqualTo(1L);
        // 文章线：溯源 URL 自动带入（D10）
        long docId = createDoc(topicId, "直提讲解");
        KbContentService.DocView doc = contentService.getDoc(docId);
        assertThat(doc.status()).isEqualTo("pending");
        assertThat(doc.sourceUrl()).isEqualTo("https://example.com/article");
        assertThat(reviewService.queue("doc", 1, 100).items()).anyMatch(item -> item.id() == docId);
        asNobody();
    }

    @Test
    void reviewGate() {
        asStaff();
        long topicId = freshTopic("审核门主题");
        long id = createKp(topicId, "审核门题", "摘要");
        // 四项强制校验缺一不可；评分要点可判定性对面试题线硬要求
        assertThatThrownBy(() -> reviewService.pass("kp", id,
                new KbReviewService.ReviewChecks("fail", "pass", "pass", "pass", null), null, null))
                .hasMessageContaining("未通过");
        assertThatThrownBy(() -> reviewService.pass("kp", id,
                new KbReviewService.ReviewChecks("pass", "na", "pass", "pass", null), null, null))
                .hasMessageContaining("可判定性");
        // 退回 → draft → 重新提交审核
        reviewService.reject("kp", id, "评分要点不可判定");
        assertThat(contentService.getKp(id).status()).isEqualTo("draft");
        reviewService.resubmit("kp", id);
        assertThat(contentService.getKp(id).status()).isEqualTo("pending");
        // 放行 → available + 复核到期时间；留痕可回溯
        passKp(id);
        KpView available = contentService.getKp(id);
        assertThat(available.status()).isEqualTo("available");
        assertThat(available.reviewDueAt()).isNotBlank();
        assertThat(reviewService.detail("kp", id).history())
                .anyMatch(review -> "pass".equals(review.getAction()))
                .anyMatch(review -> "reject".equals(review.getAction()));
        assertThatThrownBy(() -> reviewService.reject("kp", id, "再退一次"))
                .hasMessageContaining("不在审核队列");
        // 废弃：pending → deprecated
        long discarded = createKp(topicId, "废弃题", "摘要");
        reviewService.discard("kp", discarded, "质量不行");
        assertThat(contentService.getKp(discarded).status()).isEqualTo("deprecated");
        asNobody();
    }

    @Test
    void mergeFlow() {
        asStaff();
        long topicId = freshTopic("同位合并主题");
        long s1 = createKp(topicId, "HashMap 题面版本一", "摘要一");
        long s2 = createKp(topicId, "HashMap 题面版本二", "摘要二");
        long s3 = createKp(topicId, "HashMap 题面版本三", "摘要三");
        contentService.updateKp(s1, new UpdateKpReq(null, null, null, null, null, null, null, null, null,
                null, 5, null));
        contentService.updateKp(s2, new UpdateKpReq(null, null, null, null, null, null, null, null, null,
                null, 3, null));
        // 校验：不足两份不合并
        assertThatThrownBy(() -> mergeService.validateKp(List.of(s1)))
                .hasMessageContaining("至少勾选两份");
        // 校验：跨主题不合并
        long strangerTopic = freshTopic("同位合并其他主题");
        long stranger = createKp(strangerTopic, "异主题题", "摘要");
        assertThatThrownBy(() -> mergeService.validateKp(new ArrayList<>(List.of(s1, stranger))))
                .hasMessageContaining("同一主题");
        // AI 一键合并：stub 返回知识点全结构（矛盾点单列）
        stub.respond("{\"title\":\"HashMap 收敛题面\",\"summary\":\"合并摘要\",\"frameworkMd\":\"# 框架\","
                + "\"variants\":[{\"text\":\"变体问法\"}],"
                + "\"followups\":[{\"layer\":1,\"direction\":\"原理\",\"content\":\"第一层追问\"}],"
                + "\"scoringPoints\":[{\"level\":\"pass\",\"content\":\"可判定要点\"}],"
                + "\"answerSamples\":[{\"level\":\"pass\",\"content\":\"样例\"}],"
                + "\"difficulty\":\"mid\",\"levelRules\":[{\"level\":\"mid\",\"required\":true,\"stopLayer\":1}],"
                + "\"conflicts\":[\"版本一与版本二的负载因子说法不一致\"]}");
        MergeResp resp = mergeService.aiMerge("kp", List.of(s1, s2, s3));
        assertThat(resp.title()).contains("HashMap");
        assertThat(resp.conflicts()).hasSize(1);
        // 产物落库：pending 新行 + merge_sources + 稳定追问层 id（服务端分配）
        ContentItemView product = mergeService.submitKp(List.of(s1, s2, s3),
                new MergeResp("HashMap 收敛题面", "合并摘要", "# 框架", null,
                        List.of(new Variant("变体问法")),
                        List.of(new AiFollowup(1, "原理", "第一层追问")),
                        List.of(new AiScoringPoint("pass", "可判定要点")),
                        List.of(new AnswerSample("pass", "样例")),
                        "mid", List.of(new LevelRule("mid", true, 1)), List.of()));
        assertThat(product.status()).isEqualTo("pending");
        assertThat(product.mergeProduct()).isTrue();
        KpView productView = contentService.getKp(product.id());
        assertThat(productView.followups()).hasSize(1);
        assertThat(productView.followups().get(0).id()).startsWith("fu-");
        assertThat(productView.scoringPoints().get(0).id()).startsWith("sp-");
        // 放行：源标 merged（merged_into 指向产物，数据保留）、覆盖度 5+3 转入
        passKp(product.id());
        assertThat(contentService.getKp(s1).status()).isEqualTo("merged");
        assertThat(contentService.getKp(s2).status()).isEqualTo("merged");
        assertThat(contentService.getKp(s3).status()).isEqualTo("merged");
        assertThat(contentService.getKp(s1).mergedIntoId()).isEqualTo(product.id());
        assertThat(contentService.getKp(product.id()).freqCoverage()).isEqualTo(8);
        // 已下线内容不能作合并源；源可恢复（逻辑删除但数据保留，恢复后须重审）
        assertThatThrownBy(() -> mergeService.validateKp(List.of(s1, s2)))
                .hasMessageContaining("已下线");
        contentService.restore("kp", s1);
        assertThat(contentService.getKp(s1).status()).isEqualTo("draft");
        asNobody();
    }

    @Test
    void recheckAndDue() {
        asStaff();
        long topicId = freshTopic("复核到期主题");
        long id = createKp(topicId, "到期题", "摘要");
        passKp(id);
        // 未到期不在巡检列表
        assertThat(reviewService.due(1, 100).items()).noneMatch(item -> item.id() == id);
        // 模拟到期：复核时间改到昨天
        contentService.markStatus("kp", id, "available", LocalDate.now().minusDays(1).toString());
        assertThat(reviewService.due(1, 100).items()).anyMatch(item -> item.id() == id);
        // 送复审 → pending → 再放行换新复核时间
        reviewService.recheck("kp", id);
        assertThat(contentService.getKp(id).status()).isEqualTo("pending");
        passKp(id);
        assertThat(contentService.getKp(id).reviewDueAt())
                .isNotEqualTo(LocalDate.now().minusDays(1).toString());
        asNobody();
    }

    @Test
    void offlineRedLines() {
        asStaff();
        long topicId = freshTopic("下线红线主题");
        long online = createKp(topicId, "在线题", "摘要");
        passKp(online);
        // 人工编辑 available 直接生效不重审（知识库本体维护线，D4）
        contentService.updateKp(online, new UpdateKpReq("在线题-改", null, null, null, null, null, null,
                null, null, null, null, null));
        assertThat(contentService.getKp(online).status()).isEqualTo("available");
        assertThat(contentService.getKp(online).title()).isEqualTo("在线题-改");
        // 弃用下线
        contentService.offline("kp", online);
        assertThat(contentService.getKp(online).status()).isEqualTo("deprecated");
        // 红线：不可作前置依赖
        long dependent = createKp(topicId, "依赖者", "摘要");
        assertThatThrownBy(() -> contentService.updateKp(dependent,
                new UpdateKpReq(null, null, null, null, null, null, null, null, null,
                        List.of(online), null, null)))
                .hasMessageContaining("前置依赖");
        // 红线：不可作合并源
        long mate = createKp(topicId, "合并伙伴", "摘要");
        assertThatThrownBy(() -> mergeService.validateKp(List.of(online, mate)))
                .hasMessageContaining("已下线");
        // 红线：不可被资料讲解挂载
        long docId = createDoc(topicId, "挂载讲解");
        assertThatThrownBy(() -> contentService.updateDoc(docId,
                new UpdateDocReq(null, null, null, null, online, null)))
                .hasMessageContaining("挂载");
        // 恢复 → draft（数据保留可恢复，恢复后须重新提交审核）
        contentService.restore("kp", online);
        assertThat(contentService.getKp(online).status()).isEqualTo("draft");
        contentService.updateDoc(docId, new UpdateDocReq(null, null, null, null, online, null));
        assertThat(contentService.getDoc(docId).kpId()).isEqualTo(online);
        asNobody();
    }

    @Test
    void followupIdImmutable() {
        asStaff();
        long topicId = freshTopic("追问层主题");
        long id = createKp(topicId, "追问层题", "摘要");
        contentService.updateKp(id, new UpdateKpReq(null, null, null, null,
                List.of(new Followup("fu-aaa", 1, "原理", "第一层", "active")),
                null, null, null, null, null, null, null));
        assertThat(contentService.getKp(id).followups().get(0).id()).isEqualTo("fu-aaa");
        // 红线 2：既有层身份不可删除或改写，只能新增/停用
        assertThatThrownBy(() -> contentService.updateKp(id, new UpdateKpReq(null, null, null, null,
                List.of(new Followup("fu-bbb", 1, "原理", "顶替层", "active")),
                null, null, null, null, null, null, null)))
                .hasMessageContaining("红线 2");
        contentService.updateKp(id, new UpdateKpReq(null, null, null, null,
                List.of(new Followup("fu-aaa", 1, "原理", "第一层", "disabled"),
                        new Followup("fu-ccc", 2, "场景", "第二层", "active")),
                null, null, null, null, null, null, null));
        List<Followup> followups = contentService.getKp(id).followups();
        assertThat(followups).hasSize(2);
        assertThat(followups.get(0).id()).isEqualTo("fu-aaa");
        assertThat(followups.get(0).status()).isEqualTo("disabled");
        asNobody();
    }

    @Test
    void searchCatalog() {
        asStaff();
        long topicId = freshTopic("搜索主题");
        createKp(topicId, "G1 回收器回收流程", "摘要");
        createDoc(topicId, "G1 调优指南");
        assertThat(contentService.search("G1", null)).isNotEmpty();
        assertThat(contentService.search("调优指南", "doc"))
                .anyMatch(item -> "doc".equals(item.contentType()));
        assertThat(contentService.search("肯定不存在的关键词xyzq", null)).isEmpty();
        asNobody();
    }

    @Test
    void staffSessionRequired() throws Exception {
        mvc.perform(get("/api/admin/kb/topics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/kb/reviews/queue")).andExpect(status().isUnauthorized());
    }

    @Test
    void aiDifficultyAndGenericAi() throws Exception {
        asStaff();
        // 素材侧难度建议端点（向导第 3 步）
        SourceService.SourceItemView created = sourceService.create("待评级素材", "G1 混合回收的机制内容", null, "manual");
        stub.respond("{\"difficulty\":\"senior\",\"reason\":\"底层机制\"}");
        MvcResult difficulty = mvc.perform(
                        post("/api/admin/sources/" + created.id() + "/ai/difficulty").header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(difficulty.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("senior");
        // 通用 AI 改写（直达模式，内容不绑定素材行）
        stub.respond("改写后的成品");
        mvc.perform(post("/api/admin/kb/ai/rewrite").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentMd\":\"原始正文\"}"))
                .andExpect(status().isOk());
        asNobody();
    }

    private String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name + " -> " + json);
        }
        return matcher.group(1);
    }
}
