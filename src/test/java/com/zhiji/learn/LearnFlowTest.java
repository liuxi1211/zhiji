package com.zhiji.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.common.security.AuthContext;
import com.zhiji.judge.service.JudgeService;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.service.KbContentService;
import com.zhiji.kb.service.KbContentService.Followup;
import com.zhiji.kb.service.KbContentService.LevelRule;
import com.zhiji.kb.service.KbContentService.ScoringPoint;
import com.zhiji.kb.service.KbContentService.UpdateKpReq;
import com.zhiji.kb.service.KbReviewService;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.llm.StubLlmTransport;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import com.zhiji.profile.service.ProfileService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
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
 * 学习路径与练习链路（核心模块定义 4.2）：未定稿拦截、四路信号并集准入（主张/基线/JD/无信号剔除）、
 * 速览自评（quick/weak）、放弃与恢复、前置锁定、练习流（close 回写 pass）、空答与降级、越权、限额计量。
 * LLM 用 StubLlmTransport 完全替代网络；C 端会话走客户注册（test profile 验证码关闭）。
 */
@SpringBootTest
@ActiveProfiles("test")
class LearnFlowTest {

    private record AuthSession(String token, long customerId) {
    }

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
    JudgeService judgeService;

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
        // draft 态：路径被拒，且未登录 → 401
        mvc.perform(get("/api/learn/path").header("Authorization", session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEARN_PROFILE_NOT_FINALIZED"));
        mvc.perform(post("/api/learn/kp/1/quick").contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", true))))
                .andExpect(status().isUnauthorized());
        // 补齐并定稿 → 路径 200
        finalizeProfile(session, profileId);
        mvc.perform(get("/api/learn/path").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value((int) profileId))
                .andExpect(jsonPath("$.targetLevel").value("mid"));
    }

    // —— 2. 基线必问进基础阶段 ——

    @Test
    void baselineKpEntersBaselineStage() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("基线必问题", "java", null, null);
        setRequired(kpId, "mid", true, 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        String body = pathBody(session);
        assertThat((String) stageOf(body, kpId).get("kind")).isEqualTo("baseline");
        assertThat(signalsOf(body, kpId)).contains("baseline");
    }

    // —— 3. 主张锚定进领域阶段 ——

    @Test
    void claimAnchorEntersDomainStage() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("主张锚定题", "database", null, null);
        long profileId = firstProfileId(session);
        // 手动加一条带锚点的技能条并拍板（未拍板会卡住定稿）
        MvcResult added = mvc.perform(post("/api/profile/" + profileId + "/claims")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "title", "存储与缓存", "wording", "熟练使用 MySQL 索引优化",
                                "anchorIds", List.of(kpId), "source", "manual"))))
                .andExpect(status().isOk())
                .andReturn();
        long claimId = Long.parseLong(field(
                added.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        mvc.perform(post("/api/profile/" + profileId + "/claims/" + claimId + "/confirm")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk());
        finalizeProfile(session, profileId);
        String body = pathBody(session);
        Map<String, Object> stage = stageOf(body, kpId);
        assertThat((String) stage.get("kind")).isEqualTo("domain");
        assertThat((String) stage.get("domainCode")).isEqualTo("database");
        assertThat(signalsOf(body, kpId)).contains("claim");
    }

    // —— 4. JD 命中 ——

    @Test
    void jdHitKpEntersPath() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("Elasticsearch 倒排索引原理", "middleware", null, null);
        long profileId = firstProfileId(session);
        // JD 填命中标题词（非停用词）
        updateJd(session, profileId, "岗位要求：熟悉 Elasticsearch 搜索引擎调优；了解团队协作。");
        finalizeProfile(session, profileId);
        String body = pathBody(session);
        assertThat((String) stageOf(body, kpId).get("kind")).isEqualTo("domain");
        assertThat(signalsOf(body, kpId)).contains("jd");
    }

    // —— 5. 并集准入：无信号 kp 不进路径 ——

    @Test
    void kpWithoutSignalIsExcluded() throws Exception {
        AuthSession session = registerCustomer();
        long signaled = availableKp("必问题占位", "java", "mid", 1);
        long orphan = availableKp("无人问津的题", "go", null, null);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        String body = pathBody(session);
        assertThat(pathContainsKp(body, signaled)).isTrue();
        assertThat(pathContainsKp(body, orphan)).isFalse();
    }

    // —— 6. 速览自评（quick / weak / 放弃后复活） ——

    @Test
    void quickReviewStateTransitions() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("速览自评题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        // known → quick，单元完成度 +1
        mvc.perform(post("/api/learn/kp/" + kpId + "/quick").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("quick"))
                .andExpect(jsonPath("$.progressedAt").isNotEmpty());
        assertThat(kpState(pathBody(session), kpId)).isEqualTo("quick");
        // unknown → weak
        mvc.perform(post("/api/learn/kp/" + kpId + "/quick").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("weak"));
        // abandon → 进放弃清单；known 复活回 quick
        mvc.perform(post("/api/learn/kp/" + kpId + "/abandon").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("reason", "岗位用不到"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("abandoned"));
        String abandonedBody = pathBody(session);
        assertThat(pathContainsKp(abandonedBody, kpId)).isFalse();
        assertThat(abandonedContains(abandonedBody, kpId, "岗位用不到")).isTrue();
        mvc.perform(post("/api/learn/kp/" + kpId + "/quick").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("quick"));
    }

    // —— 7. 放弃与恢复（重生成保留决策） ——

    @Test
    void abandonPersistsAcrossRegenerateAndRestore() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("可放弃的题", "database", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        mvc.perform(post("/api/learn/kp/" + kpId + "/abandon").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("reason", "面试不考"))))
                .andExpect(status().isOk());
        // 重新 GET path：放弃决策保留（题移出单元、进放弃清单）
        String body = pathBody(session);
        assertThat(pathContainsKp(body, kpId)).isFalse();
        assertThat(abandonedContains(body, kpId, "面试不考")).isTrue();
        // restore → 回单元 learning
        mvc.perform(delete("/api/learn/kp/" + kpId + "/abandon").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("learning"));
        assertThat(pathContainsKp(pathBody(session), kpId)).isTrue();
    }

    // —— 8. 前置锁定 ——

    @Test
    void prerequisiteLocksUnitUntilProgressed() throws Exception {
        AuthSession session = registerCustomer();
        long topicId = ensureTopic("java", UUID.randomUUID().toString().substring(0, 8));
        long prereq = availableKpInTopic("前置知识点", "java", topicId, "mid", 1);
        long dependent = availableKpInTopic("依赖前者的知识点", "java", topicId, "mid", 1);
        setPrerequisites(dependent, List.of(prereq));
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        // prereq 未推进 → dependent 所在单元锁定
        assertThat(unitOf(pathBody(session), dependent).get("locked")).isEqualTo(true);
        // prereq quick 后解锁
        mvc.perform(post("/api/learn/kp/" + prereq + "/quick").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("known", true))))
                .andExpect(status().isOk());
        assertThat(unitOf(pathBody(session), dependent).get("locked")).isEqualTo(false);
    }

    // —— 9. 练习流：close 回写 pass / fail 保持 learning ——

    @Test
    void practiceFlowWritesBackPass() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("练习流题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        // 开局
        MvcResult started = mvc.perform(post("/api/learn/kp/" + kpId + "/practice")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopLayer").value(1))
                .andExpect(jsonPath("$.state").value("learning"))
                .andExpect(jsonPath("$.docs").isArray())
                .andReturn();
        long sessionId = Long.parseLong(field(
                started.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        String run = pointRun(kpId);
        // 题面作答：两个 pass 全命中 → correct
        stub.respond(pointsJson(false, null,
                "sp-" + run + "-a", true, "作答原话甲",
                "sp-" + run + "-b", true, "作答原话乙"));
        mvc.perform(post("/api/learn/sessions/" + sessionId + "/answers")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "answer", "作答原话甲，作答原话乙"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer.verdict").value("correct"));
        // close → grade=good（两个 pass 全命中，无 good/excellent 要点）→ state=pass
        MvcResult closed = mvc.perform(post("/api/learn/sessions/" + sessionId + "/close")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.grade").value("good"))
                .andExpect(jsonPath("$.state").value("pass"))
                .andReturn();
        assertThat(closed.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("good");
        assertThat(kpState(pathBody(session), kpId)).isEqualTo("pass");
        // 限额计量：判定按账号计（档案映射到 sys_customer 账号，用量窗口非零）
        long accountId = llmUsageService.accountIdOf(profileId);
        assertThat(accountId).isGreaterThan(0);
        assertThat(llmUsageService.customerWindowCounts().get(accountId)).isNotNull();
        assertThat(llmUsageService.customerWindowCounts().get(accountId).daily()).isGreaterThan(0);
    }

    @Test
    void practiceFailKeepsLearning() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("练习失败题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        MvcResult started = mvc.perform(post("/api/learn/kp/" + kpId + "/practice")
                        .header("Authorization", session.token())).andReturn();
        long sessionId = Long.parseLong(field(
                started.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        String run = pointRun(kpId);
        // 答错 → wrong 硬否决 → close 得 fail → state 保持 learning
        stub.respond(pointsJson(true, "把概念说反了",
                "sp-" + run + "-a", true, "答错的原话"));
        mvc.perform(post("/api/learn/sessions/" + sessionId + "/answers")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("answer", "答错的原话"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer.verdict").value("wrong"));
        mvc.perform(post("/api/learn/sessions/" + sessionId + "/close").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.grade").value("fail"))
                .andExpect(jsonPath("$.state").value("learning"));
    }

    // —— 10. 空答与降级自评 ——

    @Test
    void emptyAndDegradedSelfEval() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = availableKp("空答降级题", "java", "mid", 1);
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        MvcResult started = mvc.perform(post("/api/learn/kp/" + kpId + "/practice")
                        .header("Authorization", session.token())).andReturn();
        long sessionId = Long.parseLong(field(
                started.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        // 空答短路：verdict=empty、source=code、不调 LLM
        stub.reset();
        mvc.perform(post("/api/learn/sessions/" + sessionId + "/empty")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer.verdict").value("empty"))
                .andExpect(jsonPath("$.answer.source").value("code"));
        assertThat(stub.callCount()).isZero();
        // 降级：判定失败 → verdict=null + error_note → 自评走 self 通道
        stub.fail(new com.zhiji.common.exception.BizException(502, "SYS_PROVIDER_ERROR", "模型返回不合法 JSON"));
        MvcResult degraded = mvc.perform(post("/api/learn/sessions/" + sessionId + "/answers")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("answer", "尽力作答"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andReturn();
        long answerId = Long.parseLong(field(
                degraded.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        mvc.perform(post("/api/learn/sessions/" + sessionId + "/self-eval")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("answerId", answerId, "verdict", "correct"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("self"));
    }

    // —— 11. 越权与跨端隔离 ——

    @Test
    void crossCustomerPracticeIsNotFound() throws Exception {
        AuthSession owner = registerCustomer();
        long kpId = availableKp("越权测试题", "java", "mid", 1);
        long profileId = firstProfileId(owner);
        finalizeProfile(owner, profileId);
        MvcResult started = mvc.perform(post("/api/learn/kp/" + kpId + "/practice")
                        .header("Authorization", owner.token())).andReturn();
        long sessionId = Long.parseLong(field(
                started.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        // 客户乙访问客户甲的会话 → 404
        AuthSession stranger = registerCustomer();
        mvc.perform(get("/api/learn/sessions/" + sessionId).header("Authorization", stranger.token()))
                .andExpect(status().isNotFound());
        // 员工 token 请求 /api/learn/** → 401（该树只认客户会话）
        String staffToken = staffToken();
        mvc.perform(get("/api/learn/path").header("Authorization", staffToken))
                .andExpect(status().isUnauthorized());
    }

    // —— 12. 未上线 kp 不可练 ——

    @Test
    void practiceRejectsUnavailableKp() throws Exception {
        AuthSession session = registerCustomer();
        long pendingKp = seedKp("待审的知识点", "java", "pending");
        long profileId = firstProfileId(session);
        finalizeProfile(session, profileId);
        mvc.perform(post("/api/learn/kp/" + pendingKp + "/practice").header("Authorization", session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEARN_KP_NOT_AVAILABLE"));
    }

    // —— 助手 ——

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

    private String staffToken() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + field(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "token");
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
                                "techStack", "Java", "goodAt", "", "targetLevel", "mid",
                                "planWeeks", 12, "dailyMinutes", 60))))
                .andExpect(status().isOk());
    }

    /**
     * 补齐基础信息 + 目标职级 mid（保留已设 JD）+ 触发状态机到 confirmed + 定稿。
     * 状态机：零技能条时 recomputeStatus 置 confirmed（4.1 定稿门槛）。
     */
    private void finalizeProfile(AuthSession session, long profileId) throws Exception {
        String jd = profileService.getOwned(profileId, session.customerId()).getJdText();
        updateJd(session, profileId, jd == null ? "" : jd);
        profileService.recomputeStatus(profileId);
        mvc.perform(post("/api/profile/" + profileId + "/finalize").header("Authorization", session.token()))
                .andExpect(status().isOk());
    }

    /** 造 available 知识点并过审（可选：required 进基线 / stop_layer）；topic 每次新建。 */
    private long availableKp(String title, String domain, String requiredLevel, Integer stopLayer) {
        long topicId = ensureTopic(domain, UUID.randomUUID().toString().substring(0, 8));
        return seedAvailable(title, domain, topicId, requiredLevel, stopLayer);
    }

    /** 在指定主题下造 available 知识点（同主题多题用于前置锁定用例）。 */
    private long availableKpInTopic(String title, String domain, long topicId, String requiredLevel,
            Integer stopLayer) {
        return seedAvailable(title, domain, topicId, requiredLevel, stopLayer);
    }

    private long seedAvailable(String title, String domain, long topicId, String requiredLevel, Integer stopLayer) {
        asStaff();
        String run = UUID.randomUUID().toString().substring(0, 8);
        long id = contentService.createPending("kp", domain, topicId, title, "摘要：" + title,
                "叙述框架：" + title, "mid", null, "wizard", null, null);
        contentService.updateKp(id, new UpdateKpReq(null, null, null, null,
                List.of(new Followup("fu-" + run + "-1", 1, "原理", "第一层追问", "active")),
                List.of(new ScoringPoint("sp-" + run + "-a", "pass", "及格要点一"),
                        new ScoringPoint("sp-" + run + "-b", "pass", "及格要点二")),
                null, null,
                requiredLevel == null ? null : List.of(new LevelRule(requiredLevel, true, stopLayer)),
                null, null, null));
        kbReviewService.pass("kp", id,
                new KbReviewService.ReviewChecks("pass", "pass", "pass", "pass", null), null, null);
        asNobody();
        return id;
    }

    /** 直接把 kp 的 level_rules 改为 required（复用 updateKp）。 */
    private void setRequired(long kpId, String level, boolean required, Integer stopLayer) {
        asStaff();
        contentService.updateKp(kpId, new UpdateKpReq(null, null, null, null, null, null, null, null,
                List.of(new LevelRule(level, required, stopLayer)), null, null, null));
        asNobody();
    }

    private void setPrerequisites(long kpId, List<Long> prereqs) {
        asStaff();
        contentService.updateKp(kpId, new UpdateKpReq(null, null, null, null, null, null, null, null,
                null, prereqs, null, null));
        asNobody();
    }

    private void asStaff() {
        AuthContext.set(new AuthContext.CurrentUser(AuthContext.UserType.STAFF, 1L, "admin"));
    }

    private void asNobody() {
        AuthContext.clear();
    }

    private long seedKp(String title, String domain, String status) {
        long topicId = ensureTopic(domain, UUID.randomUUID().toString().substring(0, 8));
        KbKp kp = new KbKp();
        kp.setDomainCode(domain);
        kp.setTopicId(topicId);
        kp.setTitle(title);
        kp.setSummary(title + " 概述");
        kp.setFrameworkMd("框架");
        kp.setDifficulty("mid");
        kp.setStatus(status);
        kp.setCreatedBy(1L);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        kp.setCreatedAt(now);
        kp.setUpdatedAt(now);
        kpMapper.insert(kp);
        return kp.getId();
    }

    private long ensureTopic(String domain, String run) {
        asStaff();
        try {
            long id = kbTopicService.create(domain, "学习主题-" + run).id();
            asNobody();
            return id;
        } catch (RuntimeException e) {
            long id = kbTopicService.list(domain).stream()
                    .filter(t -> ("学习主题-" + run).equals(t.name())).findFirst().orElseThrow().id();
            asNobody();
            return id;
        }
    }

    /** 取 availableKp 建题时用的 run 后缀（评分要点 id 前缀）。 */
    private String pointRun(long kpId) {
        return contentService.getKp(kpId).scoringPoints().get(0).id().replace("sp-", "").replaceAll("-[a-z]$", "");
    }

    /** stub 判定响应：全量要点按需给 hit/quote。 */
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

    private String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name + " -> " + json);
        }
        return matcher.group(1);
    }

    // —— 路径视图解析（共享测试库会累积其他用例的 kp，断言按 id 定位而非固定下标） ——

    private String pathBody(AuthSession session) throws Exception {
        return mvc.perform(get("/api/learn/path").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rootOf(String body) {
        return new JsonMapper().readValue(body, Map.class);
    }

    /** 定位包含指定 kp 的阶段对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> stageOf(String body, long kpId) {
        List<Map<String, Object>> stages = (List<Map<String, Object>>) rootOf(body).get("stages");
        for (Map<String, Object> stage : stages) {
            if (unitHoldingKp(stage, kpId) != null) {
                return stage;
            }
        }
        throw new IllegalStateException("路径中未找到 kp " + kpId);
    }

    /** 定位包含指定 kp 的单元对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> unitOf(String body, long kpId) {
        List<Map<String, Object>> stages = (List<Map<String, Object>>) rootOf(body).get("stages");
        for (Map<String, Object> stage : stages) {
            Map<String, Object> unit = unitHoldingKp(stage, kpId);
            if (unit != null) {
                return unit;
            }
        }
        throw new IllegalStateException("路径中未找到 kp " + kpId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unitHoldingKp(Map<String, Object> stage, long kpId) {
        for (Map<String, Object> unit : (List<Map<String, Object>>) stage.get("units")) {
            for (Map<String, Object> kp : (List<Map<String, Object>>) unit.get("kps")) {
                if (((Number) kp.get("id")).longValue() == kpId) {
                    return unit;
                }
            }
        }
        return null;
    }

    /** 取指定 kp 的信号列表。 */
    @SuppressWarnings("unchecked")
    private List<String> signalsOf(String body, long kpId) {
        List<Map<String, Object>> stages = (List<Map<String, Object>>) rootOf(body).get("stages");
        for (Map<String, Object> stage : stages) {
            for (Map<String, Object> unit : (List<Map<String, Object>>) stage.get("units")) {
                for (Map<String, Object> kp : (List<Map<String, Object>>) unit.get("kps")) {
                    if (((Number) kp.get("id")).longValue() == kpId) {
                        return (List<String>) kp.get("signals");
                    }
                }
            }
        }
        throw new IllegalStateException("路径中未找到 kp " + kpId);
    }

    /** kp 是否出现在路径中（任意阶段任意单元）。 */
    @SuppressWarnings("unchecked")
    private boolean pathContainsKp(String body, long kpId) {
        List<Map<String, Object>> stages = (List<Map<String, Object>>) rootOf(body).get("stages");
        for (Map<String, Object> stage : stages) {
            if (unitHoldingKp(stage, kpId) != null) {
                return true;
            }
        }
        return false;
    }

    /** 取指定 kp 在路径中的学习状态（未在路径中则报错）。 */
    @SuppressWarnings("unchecked")
    private String kpState(String body, long kpId) {
        List<Map<String, Object>> stages = (List<Map<String, Object>>) rootOf(body).get("stages");
        for (Map<String, Object> stage : stages) {
            for (Map<String, Object> unit : (List<Map<String, Object>>) stage.get("units")) {
                for (Map<String, Object> kp : (List<Map<String, Object>>) unit.get("kps")) {
                    if (((Number) kp.get("id")).longValue() == kpId) {
                        return (String) kp.get("state");
                    }
                }
            }
        }
        throw new IllegalStateException("路径中未找到 kp " + kpId);
    }

    /** 放弃清单是否含指定 kp（且原因匹配）。 */
    @SuppressWarnings("unchecked")
    private boolean abandonedContains(String body, long kpId, String reason) {
        Object raw = rootOf(body).get("abandoned");
        if (!(raw instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            Map<String, Object> row = (Map<String, Object>) item;
            if (((Number) row.get("kpId")).longValue() == kpId && reason.equals(row.get("reason"))) {
                return true;
            }
        }
        return false;
    }
}
