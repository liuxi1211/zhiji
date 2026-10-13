package com.zhiji.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.judge.service.JudgeService;
import com.zhiji.judge.service.JudgeService.AnswerResp;
import com.zhiji.judge.service.JudgeService.SessionView;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.Followup;
import com.zhiji.kb.service.KbContentService.KpView;
import com.zhiji.kb.service.KbContentService.LevelRule;
import com.zhiji.kb.service.KbContentService.ScoringPoint;
import com.zhiji.kb.service.KbContentService.UpdateKpReq;
import com.zhiji.kb.service.KbReviewService;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.llm.StubLlmTransport;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 判定引擎链路（核心模块定义 6.1）：会话建立与停止点���照、答况四分流（空答短路）、
 * 引用强制（引不出原话改未命中）、wrong 硬否决、降级自评、关闭评级（档位公式）、鉴权边界。
 * LLM 用 StubLlmTransport 完全替代网络；B 端会话走员工登录。
 */
@SpringBootTest
@ActiveProfiles("test")
class JudgeFlowTest {

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
    JudgeService judgeService;

    @Autowired
    KbContentService contentService;

    @Autowired
    KbTopicService topicService;

    @Autowired
    KbReviewService reviewService;

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
                                .writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
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

    /** 建一个 available 且带评分要点/追问层/职级规则的知识点，返回 kpId。 */
    private long availableKp(String title) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        long topicId;
        try {
            topicId = topicService.create("java", "判定主题-" + run).id();
        } catch (BizException e) {
            topicId = topicService.list("java").stream()
                    .filter(t -> ("判定主题-" + run).equals(t.name()))
                    .findFirst().orElseThrow().id();
        }
        long id = contentService.createPending("kp", "java", topicId, title, "摘要：" + title,
                "叙述框架：" + title, "mid", null, "wizard", null, null);
        // 结构化列：两层追问；两个 pass 要点、一个 good、一个 excellent；mid 职级 stop_layer=1
        contentService.updateKp(id, new UpdateKpReq(null, null, null, null,
                List.of(new Followup("fu-" + run + "-1", 1, "原理", "第一层追问内容", "active"),
                        new Followup("fu-" + run + "-2", 2, "场景", "第二层追问内容", "active")),
                List.of(new ScoringPoint("sp-" + run + "-a", "pass", "及格要点一"),
                        new ScoringPoint("sp-" + run + "-b", "pass", "及格要点二"),
                        new ScoringPoint("sp-" + run + "-g", "good", "良好要点"),
                        new ScoringPoint("sp-" + run + "-e", "excellent", "优秀要点")),
                null, null, List.of(new LevelRule("mid", true, 1)), null, null, null));
        reviewService.pass("kp", id,
                new KbReviewService.ReviewChecks("pass", "pass", "pass", "pass", null), null, null);
        return id;
    }

    /** stub 判定响应：全量四要点按需给 hit/quote。 */
    private String pointsJson(boolean wrong, String wrongNote, Object... idHitQuote) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < idHitQuote.length; i += 3) {
            if (!points.isEmpty()) {
                points.append(',');
            }
            points.append("{\"id\":\"").append(idHitQuote[i]).append("\",\"hit\":").append(idHitQuote[i + 1])
                    .append(",\"quote\":\"").append(idHitQuote[i + 2]).append("\"}");
        }
        return "{\"wrong\":" + wrong + ",\"wrongNote\":\"" + (wrongNote == null ? "" : wrongNote)
                + "\",\"points\":[" + points + "]}";
    }

    @Test
    void sessionCreateWithStopLayerSnapshot() {
        asStaff();
        long kpId = availableKp("停止点快照题");
        SessionView session = judgeService.createSession(kpId, "mid");
        // mid 的 level_rules 标了 stop_layer=1：快照进会话（D4）
        assertThat(session.stopLayer()).isEqualTo(1);
        assertThat(session.status()).isEqualTo("open");
        assertThat(session.followups()).hasSize(2);
        assertThat(session.scoringPoints()).hasSize(4);
        // 非 available 内容不可判
        long pendingKp = contentService.createPending("kp", "java", topicOf(kpId), "待审题", "摘要", "正文", "mid",
                null, "wizard", null, null);
        assertThatThrownBy(() -> judgeService.createSession(pendingKp, "mid"))
                .hasMessageContaining("可用状态");
        // 职级不合法
        assertThatThrownBy(() -> judgeService.createSession(kpId, "boss"))
                .hasMessageContaining("目标职级");
        asNobody();
    }

    private long topicOf(long kpId) {
        return contentService.getKp(kpId).topicId();
    }

    @Test
    void verdictRoutingAndQuoteEnforcement() {
        asStaff();
        long kpId = availableKp("答况四分流题");
        String run = pointRun(kpId);
        SessionView session = judgeService.createSession(kpId, "mid");

        // 空答短路：source=code、不调 LLM
        AnswerResp emptyResp = judgeService.submitEmpty(session.id(), null);
        assertThat(emptyResp.answer().verdict()).isEqualTo("empty");
        assertThat(emptyResp.answer().source()).isEqualTo("code");
        assertThat(stub.callCount()).isZero();

        // 标准命中：pass 命中 → correct；快照含未命中项
        stub.respond(pointsJson(false, null,
                "sp-" + run + "-a", true, "数据结构是数组加链表",
                "sp-" + run + "-b", false, ""));
        AnswerResp correct = judgeService.submitAnswer(session.id(), null, "数据结构是数组加链表，其余略");
        assertThat(correct.answer().verdict()).isEqualTo("correct");
        assertThat(correct.answer().points()).hasSize(4);
        assertThat(correct.answer().points()).anyMatch(p -> p.hit() && "pass".equals(p.level()));
        assertThat(correct.answer().points()).anyMatch(p -> !p.hit() && "excellent".equals(p.level()));

        // 引用强制：hit=true 但引不出原话 → 改 miss → 答况 missing
        stub.respond(pointsJson(false, null,
                "sp-" + run + "-a", true, "作答里根本没这句话"));
        AnswerResp missing = judgeService.submitAnswer(session.id(), null, "随便说说负载因子");
        assertThat(missing.answer().verdict()).isEqualTo("missing");

        // wrong 硬否决优先于命中
        stub.respond(pointsJson(true, "把负载因子的触发时机说反了",
                "sp-" + run + "-a", true, "数据结构是数组加链表"));
        AnswerResp wrong = judgeService.submitAnswer(session.id(), null, "数据结构是数组加链表，但负载因子到 0.5 就扩容");
        assertThat(wrong.answer().verdict()).isEqualTo("wrong");
        assertThat(wrong.answer().wrongNote()).contains("说反");
        asNobody();
    }

    /** 取 availableKp 建题时用的 run 后缀（评分要点 id 前缀）。 */
    private String pointRun(long kpId) {
        KpView kp = contentService.getKp(kpId);
        return kp.scoringPoints().get(0).id().replace("sp-", "").replaceAll("-[a-z]$", "");
    }

    @Test
    void degradedSelfEvalThenClose() {
        asStaff();
        long kpId = availableKp("降级自评题");
        String run = pointRun(kpId);
        SessionView session = judgeService.createSession(kpId, "mid");

        // provider error → 降级：verdict=null + error_note
        stub.fail(new BizException(502, "SYS_PROVIDER_ERROR", "模型返回两次不合法 JSON"));
        AnswerResp degraded = judgeService.submitAnswer(session.id(), null, "尽力作答的内容");
        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.answer().verdict()).isNull();
        assertThat(degraded.answer().errorNote()).contains("SYS_PROVIDER_ERROR");

        // 已有结论的行不可自评；四值之外不可自评
        assertThatThrownBy(() -> judgeService.selfEval(session.id(), degraded.answer().id(), "bogus"))
                .hasMessageContaining("取值不合法");

        // 自评 correct → 落库、source=self、会话标 degraded
        JudgeService.AnswerView self = judgeService.selfEval(session.id(), degraded.answer().id(), "correct");
        assertThat(self.verdict()).isEqualTo("correct");
        assertThat(self.source()).isEqualTo("self");
        assertThat(judgeService.get(session.id()).degraded()).isTrue();
        assertThatThrownBy(() -> judgeService.selfEval(session.id(), degraded.answer().id(), "wrong"))
                .hasMessageContaining("不可自评");

        // 补齐另一 pass 命中 → close 得 pass（self 行计入档位，D7）
        stub.respond(pointsJson(false, null,
                "sp-" + run + "-a", true, "含及格要点一的原话",
                "sp-" + run + "-b", true, "含及格要点二的原话"));
        judgeService.submitAnswer(session.id(), null, "含及格要点一的原话，含及格要点二的原话");
        SessionView closed = judgeService.close(session.id());
        assertThat(closed.status()).isEqualTo("closed");
        assertThat(closed.grade()).isEqualTo("pass");
        assertThat(closed.gradeNote()).isNotBlank();

        // 关闭后不能继续作答、不能重复关闭
        assertThatThrownBy(() -> judgeService.submitAnswer(session.id(), null, "再答一问"))
                .hasMessageContaining("已关闭");
        assertThatThrownBy(() -> judgeService.close(session.id()))
                .hasMessageContaining("已关闭");
        asNobody();
    }

    @Test
    void wrongHardVetoAndTitleRequired() {
        asStaff();
        long kpId = availableKp("硬否决题");
        SessionView session = judgeService.createSession(kpId, "mid");
        // 题面未答不能 close
        assertThatThrownBy(() -> judgeService.close(session.id()))
                .hasMessageContaining("还没有作答");
        // 只答追问层也不算题面已答
        judgeService.submitEmpty(session.id(), null);
        assertThatThrownBy(() -> judgeService.close(session.id()))
                .hasMessageContaining("题面尚未作答");
        // 题面 wrong → close 得 fail（硬否决）
        stub.respond("{\"wrong\":true,\"wrongNote\":\"概念说反\",\"points\":[]}");
        judgeService.submitAnswer(session.id(), null, "一个带概念错误的回答");
        SessionView closed = judgeService.close(session.id());
        assertThat(closed.grade()).isEqualTo("fail");
        assertThat(closed.gradeNote()).contains("硬否决");
        asNobody();
    }

    @Test
    void apiFlowAndAuthBoundary() throws Exception {
        // 未登录 401
        mvc.perform(get("/api/admin/judge/sessions")).andExpect(status().isUnauthorized());
        // 客户 token 打不进管理端判定接口（双树隔离）
        mvc.perform(get("/api/admin/judge/sessions").header("Authorization", "Bearer customer-token-x"))
                .andExpect(status().isUnauthorized());

        asStaff();
        long kpId = availableKp("API 全链路题");
        String run = pointRun(kpId);
        String kpList = mvc.perform(get("/api/admin/judge/kps").param("keyword", "API 全链路")
                        .header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(kpList).contains("API 全链路题");

        String sessionBody = mvc.perform(post("/api/admin/judge/sessions").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kpId\":" + kpId + ",\"level\":\"mid\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long sessionId = Long.parseLong(field(sessionBody, "id"));

        stub.respond(pointsJson(false, null,
                "sp-" + run + "-a", true, "要点一的原话",
                "sp-" + run + "-b", true, "要点二的原话",
                "sp-" + run + "-g", true, "良好要点的原话",
                "sp-" + run + "-e", true, "优秀要点的原话"));
        mvc.perform(post("/api/admin/judge/sessions/" + sessionId + "/answers").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"要点一的原话，要点二的原话，良好要点的原话，优秀要点的原话\"}"))
                .andExpect(status().isOk());
        // 追问层作答（超停止点的层可判但 in-scope 过滤——mid 停在第 1 层，第 2 层不计档）
        stub.respond(pointsJson(false, null, "sp-" + run + "-e", true, "优秀要点的原话"));
        mvc.perform(post("/api/admin/judge/sessions/" + sessionId + "/answers").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followupId\":\"" + followupId(kpId, 2) + "\",\"answer\":\"优秀要点的原话\"}"))
                .andExpect(status().isOk());
        // close：全命中 → excellent
        String closed = mvc.perform(post("/api/admin/judge/sessions/" + sessionId + "/close")
                        .header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(closed).contains("\"grade\":\"excellent\"");

        // 空答端点（source=code）
        String sessionBody2 = mvc.perform(post("/api/admin/judge/sessions").header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kpId\":" + kpId + ",\"level\":\"junior\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long sessionId2 = Long.parseLong(field(sessionBody2, "id"));
        String emptyBody = mvc.perform(
                        post("/api/admin/judge/sessions/" + sessionId2 + "/empty").header("Authorization", staffToken)
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(emptyBody).contains("\"verdict\":\"empty\"").contains("\"source\":\"code\"");

        // 历史列表
        String list = mvc.perform(get("/api/admin/judge/sessions").header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(list).contains("\"grade\":\"excellent\"");
        asNobody();
    }

    private String followupId(long kpId, int layer) {
        return contentService.getKp(kpId).followups().stream()
                .filter(f -> f.layer() == layer)
                .findFirst().orElseThrow().id();
    }

    private String field(String json, String name) {
        var matcher = java.util.regex.Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name + " -> " + json);
        }
        return matcher.group(1);
    }
}
