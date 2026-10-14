package com.zhiji.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.LevelRule;
import com.zhiji.kb.service.KbContentService.UpdateKpReq;
import com.zhiji.kb.service.KbReviewService;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.learn.domain.LearnState;
import com.zhiji.learn.service.LearnStateService;
import com.zhiji.llm.StubLlmTransport;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import com.zhiji.profile.service.ProfileService;
import com.zhiji.review.service.ReviewService;
import com.zhiji.review.service.ReviewService.KpWeightCtx;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 今日复习链路（核心模块定义 4.3）：未定稿拦截、到期复测（due_at NULL 视为到期）、阶梯递增、
 * 弱点加权、抽查验伪（不回写五态）、技能条新题、随机抽取（池空 400）、动态权重纯函数、
 * 自评校验与域过滤。LLM 用 StubLlmTransport 替代（本模块纯算法，判定模式不在链路内）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewFlowTest {

    private record AuthSession(String token, long customerId) {
    }

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        StubLlmTransport stubLlmTransport() {
            return new StubLlmTransport();
        }

        /** 固定序列随机：nextDouble 依次返回注入值，用尽后回落 0.5（不触发抽查、加权取中段）。 */
        @Bean
        @Primary
        Random testRandom() {
            return new ScriptedRandom();
        }
    }

    /** 可脚本化随机（测试专用）：按 offer 顺序吐值，用尽回落 0.5。 */
    static class ScriptedRandom extends Random implements RandomOffer {

        private final java.util.Deque<Double> script = new java.util.ArrayDeque<>();

        @Override
        public void offer(double value) {
            script.offer(value);
        }

        @Override
        public double nextDouble() {
            return script.isEmpty() ? 0.5 : script.poll();
        }
    }

    private interface RandomOffer {
        void offer(double value);
    }

    @Autowired
    WebApplicationContext context;

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

    @Autowired
    KbContentService contentService;

    @Autowired
    KbKpMapper kpMapper;

    @Autowired
    ProfileService profileService;

    @Autowired
    LearnStateService learnStateService;

    @Autowired
    Random testRandom;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        stub.reset();
        llmConfigService.saveRouteConfig(llmConfigService.defaultRouteConfig());
        llmConfigService.saveLimits(LlmLimits.defaults());
        llmUsageService.deleteByDate(llmUsageService.today());
    }

    // —— 1. 未定稿拦截 ——

    @Test
    void unfinalizedProfileIsBlocked() throws Exception {
        AuthSession session = registerCustomer();
        long profileId = firstProfileId(session);
        mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEARN_PROFILE_NOT_FINALIZED"));
        mvc.perform(get("/api/review/draw").header("Authorization", session.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/review/kp/1/result").contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", true))))
                .andExpect(status().isUnauthorized());
        finalizeProfile(session, profileId);
        mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetLevel").value("senior"));
    }

    // —— 2. 到期复测：due_at NULL 视为到期 → 自评 → 排期后移出列 ——

    @Test
    void dueKpEntersTodayAndSchedulesAfterKnown() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("到期复测题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        // 学习推进（quick）→ progressed 且 due_at NULL → 今日清单 tag=due
        quickEval(session, kpId, true);
        MvcResult today = mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isOk()).andReturn();
        Map<String, Object> item = itemOf(today.getResponse().getContentAsString(StandardCharsets.UTF_8), kpId);
        assertThat(tagsOf(item)).contains("due");
        // 自评"会" → interval=1、due_at=明天起、log 落库
        mvc.perform(post("/api/review/kp/" + kpId + "/result").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", true, "mode", "today"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalDays").value(1))
                .andExpect(jsonPath("$.dueAt").isNotEmpty());
        // due_at 后移 → 今日清单不再出现该题
        MvcResult after = mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isOk()).andReturn();
        assertThat(itemOfOrNull(after.getResponse().getContentAsString(StandardCharsets.UTF_8), kpId)).isNull();
        // 手工把 due_at 改回过去 → 重新出现 tag=due
        LearnState row = learnStateService.getOwned(profileId, kpId);
        row.setDueAt("2000-01-01 00:00:00");
        row.setUpdatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        learnStateService.updateRow(row);
        MvcResult again = mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isOk()).andReturn();
        assertThat(tagsOf(itemOf(again.getResponse().getContentAsString(StandardCharsets.UTF_8), kpId)))
                .contains("due");
    }

    // —— 3. 阶梯递增：连续 known → interval 1→2→4 ——

    @Test
    void intervalLadderIncreasesOnStreak() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("阶梯递增题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        quickEval(session, kpId, true);
        int[] expected = {1, 2, 4};
        for (int exp : expected) {
            mvc.perform(post("/api/review/kp/" + kpId + "/result").header("Authorization", session.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new JsonMapper().writeValueAsString(Map.of("known", true, "mode", "today"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.intervalDays").value(exp));
        }
    }

    // —— 4. 弱点加权：weak 态进今日清单 tag=weak ——

    @Test
    void weakKpEntersToday() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("弱点加权题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        quickEval(session, kpId, false);
        MvcResult today = mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isOk()).andReturn();
        Map<String, Object> item = itemOf(today.getResponse().getContentAsString(StandardCharsets.UTF_8), kpId);
        assertThat(tagsOf(item)).contains("weak");
    }

    // —— 5. 抽查验伪：quick 态 spot 弹出；自评"不会"→ fail_count+1、打回、五态不动 ——

    @Test
    void spotCheckAccumulatesEvidenceWithoutStatusRewrite() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("抽查验伪题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        quickEval(session, kpId, true);
        // 随机 stub 返回 0.0 → spot 触发
        offerRandom(0.0);
        MvcResult today = mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isOk()).andReturn();
        Map<String, Object> item = itemOf(today.getResponse().getContentAsString(StandardCharsets.UTF_8), kpId);
        assertThat(tagsOf(item)).contains("spot");
        // 自评"不会"：fail_count=1、due_at=now（打回复测队列）、interval=0
        mvc.perform(post("/api/review/kp/" + kpId + "/result").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", false, "mode", "today"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failCount").value(1))
                .andExpect(jsonPath("$.intervalDays").value(0))
                .andExpect(jsonPath("$.state").value("quick"));
        // R2 核心断言：learn_state.status 仍 quick（不回写五态）
        assertThat(learnStateService.getOwned(profileId, kpId).getStatus()).isEqualTo("quick");
    }

    // —— 6. 技能条新题：锚定未学 tag=new；自评不建排期 ——

    @Test
    void anchoredNewKpAppearsAndResultDoesNotSchedule() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("锚定新题", "database", null, null);
        long profileId = firstProfileId(session);
        addClaim(session, profileId, kpId);
        finalizeProfile(session, profileId);
        MvcResult today = mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isOk()).andReturn();
        Map<String, Object> item = itemOf(today.getResponse().getContentAsString(StandardCharsets.UTF_8), kpId);
        assertThat(tagsOf(item)).contains("new");
        // 自评"会"：不建 learn_state 排期（无 progressed 行）、不落 due_at
        mvc.perform(post("/api/review/kp/" + kpId + "/result").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", true, "mode", "draw"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("learning"))
                .andExpect(jsonPath("$.dueAt").doesNotExist());
        assertThat(learnStateService.getOwned(profileId, kpId)).isNull();
    }

    // —— 7. 空清单 ——

    @Test
    void emptyTodayWhenNothingDue() throws Exception {
        AuthSession session = registerCustomer();
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        mvc.perform(get("/api/review/today").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.stats.due").value(0))
                .andExpect(jsonPath("$.stats.weak").value(0))
                .andExpect(jsonPath("$.stats.newCount").value(0));
    }

    // —— 8. 随机抽取：池内命中；exclude 全排除 → REVIEW_POOL_EMPTY ——

    @Test
    void drawHonorsPoolAndExclude() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("抽取池题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        quickEval(session, kpId, true);
        // 复习池命中该题（随机 stub 0.5，不触发抽查路径）
        offerRandom(0.5);
        mvc.perform(get("/api/review/draw").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.kpId").value((int) kpId))
                .andExpect(jsonPath("$.poolSize").value(1));
        // exclude 全排除 → 池空 400
        mvc.perform(get("/api/review/draw").header("Authorization", session.token())
                        .param("exclude", String.valueOf(kpId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_POOL_EMPTY"));
    }

    // —— 9. 动态权重纯函数（R7：规则全在代码） ——

    @Test
    void weightPureFunctionFollowsDynamicRules() {
        double base = ReviewService.weightOf(new KpWeightCtx("pass", false, "mid", 0, false));
        assertThat(base).isEqualTo(1.0);
        assertThat(ReviewService.weightOf(new KpWeightCtx("pass", true, "mid", 0, false))).isEqualTo(3.0);
        assertThat(ReviewService.weightOf(new KpWeightCtx("weak", false, "mid", 0, false))).isEqualTo(3.0);
        assertThat(ReviewService.weightOf(new KpWeightCtx("quick", false, "mid", 0, false))).isEqualTo(0.5);
        assertThat(ReviewService.weightOf(new KpWeightCtx("learning", false, "mid", 0, false))).isEqualTo(0.8);
        // 连对衰减：×1/(1+streak)
        assertThat(ReviewService.weightOf(new KpWeightCtx("pass", false, "mid", 2, false)))
                .isEqualTo(1.0 / 3.0);
        // 连错掺低难：junior/mid 已学 ×3；senior 不掺；new 不掺
        assertThat(ReviewService.weightOf(new KpWeightCtx("pass", false, "junior", 0, true))).isEqualTo(3.0);
        assertThat(ReviewService.weightOf(new KpWeightCtx("pass", false, "senior", 0, true))).isEqualTo(1.0);
        assertThat(ReviewService.weightOf(new KpWeightCtx("learning", false, "junior", 0, true))).isEqualTo(0.8);
        assertThat(ReviewService.shouldIncludeSpot(0.0)).isTrue();
        assertThat(ReviewService.shouldIncludeSpot(0.9)).isFalse();
    }

    // —— 10. result 校验：mode 非法 400；known 缺失 400 ——

    @Test
    void resultValidation() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("校验题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        mvc.perform(post("/api/review/kp/" + kpId + "/result").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", true, "mode", "bad"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/review/kp/" + kpId + "/result").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("mode", "today"))))
                .andExpect(status().isBadRequest());
    }

    // —— 11. 域过滤：draw 只出该域 kp ——

    @Test
    void drawFiltersByDomain() throws Exception {
        AuthSession session = registerCustomer();
        long javaKp = availableKp("Java 域抽取题", "java", "mid", 1);
        long dbKp = availableKp("数据库域抽取题", "database", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        quickEval(session, javaKp, true);
        quickEval(session, dbKp, true);
        for (int i = 0; i < 5; i++) {
            offerRandom(0.5);
            MvcResult result = mvc.perform(get("/api/review/draw").header("Authorization", session.token())
                            .param("domainCode", "database"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.item.kpId").value((int) dbKp))
                    .andReturn();
        }
    }

    // —— 助手 ——

    private void offerRandom(double value) {
        ((RandomOffer) testRandom).offer(value);
    }

    private interface StubConfigScript {
        void offer(double value);
    }

    private AuthSession registerCustomer() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/customer/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "username", "cu" + System.nanoTime(),
                                "password", "Passw0rd",
                                "captchaId", "test",
                                "captchaCode", "test"))))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new AuthSession("Bearer " + field(body, "token"), Long.parseLong(field(body, "customerId")));
    }

    private long firstProfileId(AuthSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/profile").header("Authorization", session.token()))
                .andExpect(status().isOk()).andReturn();
        return Long.parseLong(field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
    }

    private void updateJd(AuthSession session, long profileId, String jd) throws Exception {
        mvc.perform(put("/api/profile/" + profileId).header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "name", "我的档案", "resumeText", "三年 Java 后端",
                                "years", 4, "position", "Java 后端", "jdText", jd,
                                "techStack", "Java", "goodAt", "", "targetLevel", "senior",
                                "planWeeks", 12, "dailyMinutes", 60))))
                .andExpect(status().isOk());
    }

    private void finalizeProfile(AuthSession session, long profileId) throws Exception {
        String jd = profileService.getOwned(profileId, session.customerId()).getJdText();
        updateJd(session, profileId, jd == null ? "" : jd);
        profileService.recomputeStatus(profileId);
        mvc.perform(post("/api/profile/" + profileId + "/finalize").header("Authorization", session.token()))
                .andExpect(status().isOk());
    }

    private void quickEval(AuthSession session, long kpId, boolean known) throws Exception {
        mvc.perform(post("/api/learn/kp/" + kpId + "/quick").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", known))))
                .andExpect(status().isOk());
    }

    private void addClaim(AuthSession session, long profileId, long kpId) throws Exception {
        MvcResult added = mvc.perform(post("/api/profile/" + profileId + "/claims")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "title", "存储与缓存", "wording", "熟练使用 MySQL 索引优化",
                                "anchorIds", List.of(kpId), "source", "manual"))))
                .andExpect(status().isOk()).andReturn();
        long claimId = Long.parseLong(field(
                added.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        mvc.perform(post("/api/profile/" + profileId + "/claims/" + claimId + "/confirm")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk());
    }

    private long availableKp(String title, String domain, String requiredLevel, Integer stopLayer) {
        asStaff();
        long topicId;
        try {
            topicId = kbTopicService.create(domain, "复习主题-" + UUID.randomUUID().toString().substring(0, 8), null).id();
        } finally {
            asNobody();
        }
        String run = UUID.randomUUID().toString().substring(0, 8);
        asStaff();
        try {
            long id = contentService.createPending("kp", domain, topicId, title, "摘要：" + title,
                    "叙述框架：" + title, "mid", null, "wizard", null, null);
            contentService.updateKp(id, new UpdateKpReq(null, null, null, null, null,
                    null, null, null,
                    requiredLevel == null ? null : List.of(new LevelRule(requiredLevel, true, stopLayer)),
                    null, null, null));
            kbReviewService.pass("kp", id,
                    new KbReviewService.ReviewChecks("pass", "pass", "pass", "pass", null), null, null);
            return id;
        } finally {
            asNobody();
        }
    }

    private void asStaff() {
        AuthContext.set(new AuthContext.CurrentUser(AuthContext.UserType.STAFF, 1L, "admin"));
    }

    private void asNobody() {
        AuthContext.clear();
    }

    private String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name + " -> " + json);
        }
        return matcher.group(1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rootOf(String body) {
        return new JsonMapper().readValue(body, Map.class);
    }

    /** 定位今日清单中指定 kp 的卡片；kpId=-1 时取第一个（域过滤用例单题池）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> itemOfOrNull(String body, long kpId) {
        Object raw = rootOf(body).get("items");
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        for (Object entry : list) {
            Map<String, Object> item = (Map<String, Object>) entry;
            if (kpId < 0 || ((Number) item.get("kpId")).longValue() == kpId) {
                return item;
            }
        }
        return null;
    }

    private Map<String, Object> itemOf(String body, long kpId) {
        Map<String, Object> item = itemOfOrNull(body, kpId);
        if (item == null) {
            throw new IllegalStateException("清单/抽取结果中未找到 kp " + kpId + " -> " + body);
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<String> tagsOf(Map<String, Object> item) {
        return (List<String>) item.get("tags");
    }
}
