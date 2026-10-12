package com.zhiji.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.kb.service.KbTopicService;
import com.zhiji.llm.StubLlmTransport;
import com.zhiji.llm.domain.LlmLimits;
import com.zhiji.llm.service.LlmConfigService;
import com.zhiji.llm.service.LlmUsageService;
import com.zhiji.profile.service.ProfileAiService;
import com.zhiji.profile.service.ProfileService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 个人档案链路（核心模块定义 4.1）：注册即建默认档案、3 份上限、当前档案切换、
 * AI 起草（锚点越界丢弃）、技能条拍板与状态机、定稿门槛、项目素材、C 端 kp 搜索。
 * LLM 用 StubLlmTransport 完全替代网络；C 端会话走客户注册（test profile 验证码关闭）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProfileFlowTest {

    /** 注册即登录：token + customerId（后续 service 直调用例需要客户 id）。 */
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
    ProfileService profileService;

    @Autowired
    ProfileAiService profileAiService;

    @Autowired
    KbTopicService kbTopicService;

    @Autowired
    KbKpMapper kpMapper;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        stub.reset();
        // 测试库共享（临时 SQLite），每个用例前重置路由与限额，保证新增入口可调用
        llmConfigService.saveRouteConfig(llmConfigService.defaultRouteConfig());
        llmConfigService.saveLimits(LlmLimits.defaults());
        llmUsageService.deleteByDate(llmUsageService.today());
    }

    // —— 1. 注册即建默认档案 ——

    @Test
    void registerCreatesDefaultProfile() throws Exception {
        AuthSession session = registerCustomer();
        mvc.perform(get("/api/profile").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("我的档案"))
                .andExpect(jsonPath("$[0].status").value("draft"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    // —— 2. 每用户最多 3 份 ——

    @Test
    void profileLimitIsThree() throws Exception {
        AuthSession session = registerCustomer();
        createProfile(session, "Go 方向");
        createProfile(session, "ES 方向");
        mvc.perform(post("/api/profile").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("name", "第 4 份"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRF_PROFILE_LIMIT"));
    }

    // —— 3. 当前档案切换（部分唯一索引兜底） ——

    @Test
    void activateSwitchesActiveProfile() throws Exception {
        AuthSession session = registerCustomer();
        long secondId = createProfile(session, "Go 方向");
        mvc.perform(post("/api/profile/" + secondId + "/activate").header("Authorization", session.token()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/profile").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value((int) secondId))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].active").value(false));
    }

    // —— 4. 越权防护（404 不泄露存在性） ——

    @Test
    void crossCustomerAccessIsNotFound() throws Exception {
        AuthSession owner = registerCustomer();
        long profileId = firstProfileId(owner);
        AuthSession stranger = registerCustomer();
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", stranger.token()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/profile/" + profileId + "/activate").header("Authorization", stranger.token()))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/profile/" + profileId)
                        .header("Authorization", stranger.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("name", "越权改名"))))
                .andExpect(status().isNotFound());
    }

    // —— 5. AI 起草：锚点越界/无效条目被丢弃、合法条目返回；产物不落库 ——

    @Test
    void aiDraftRejectsInvalidAnchors() throws Exception {
        AuthSession session = registerCustomer();
        // 标题含 techStack 关键词，保证走候选池的关键词命中路径（共享库高频 Top50 会被历史数据挤占）
        long kpId = seedKp("Java 集合 HashMap 的底层实现与扩容机制", "available");
        long profileId = firstProfileId(session);
        // 默认档案输入全空会被拒；先补齐简历与技术栈
        mvc.perform(put("/api/profile/" + profileId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("mid")))
                .andExpect(status().isOk());
        stub.respond("{\"claims\":["
                + "{\"wording\":\"熟悉 HashMap 的底层实现与扩容机制\",\"anchorKpIds\":[" + kpId + "]},"
                + "{\"wording\":\"精通量子计算调度\",\"anchorKpIds\":[999999]},"
                + "{\"wording\":\"\",\"anchorKpIds\":[" + kpId + "]}]}");
        MvcResult result = mvc.perform(post("/api/profile/" + profileId + "/ai/claims")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("HashMap 的底层实现与扩容机制");
        assertThat(body).doesNotContain("量子计算");
        // 产物不落库：技能条仍为 0 条
        mvc.perform(get("/api/profile").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].claimCount").value(0));
        stub.fail(new RuntimeException("provider down"));
        assertThatThrownBy(() -> profileAiService.draftClaims(
                profileService.getOwned(profileId, session.customerId())))
                .isInstanceOf(RuntimeException.class);
    }

    // —— 6. 拍板与状态机：全部确认→confirmed；新增未确认→回 draft；定稿/撤销 ——

    @Test
    void claimConfirmAndStateMachine() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = seedKp("Redis 持久化机制", "available");
        long profileId = firstProfileId(session);
        // 采纳一条 AI 草稿（source=ai），锚点标题实时反查
        MvcResult added = mvc.perform(post("/api/profile/" + profileId + "/claims")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "wording", "熟悉 Redis 持久化机制", "anchorIds", List.of(kpId), "source", "ai"))))
                .andExpect(status().isOk()).andReturn();
        long firstClaimId = Long.parseLong(
                field(added.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.claims[0].status").value("unconfirmed"))
                .andExpect(jsonPath("$.claims[0].anchors[0].id").value((int) kpId))
                .andExpect(jsonPath("$.claims[0].source").value("ai"));
        // 拍板 → 全部确认 → 档案自动 confirmed
        mvc.perform(post("/api/profile/" + profileId + "/claims/" + firstClaimId + "/confirm")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.status").value("confirmed"));
        // 再加一条未确认（手动 source=manual）→ 自动回 draft；此时定稿被拒
        MvcResult secondAdded = mvc.perform(post("/api/profile/" + profileId + "/claims")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "wording", "熟悉 G1 回收流程", "anchorIds", List.of(kpId)))))
                .andExpect(status().isOk()).andReturn();
        long secondClaimId = Long.parseLong(
                field(secondAdded.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        mvc.perform(get("/api/profile").header("Authorization", session.token()))
                .andExpect(jsonPath("$[0].status").value("draft"))
                .andExpect(jsonPath("$[0].unconfirmedCount").value(1));
        mvc.perform(put("/api/profile/" + profileId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("mid")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/profile/" + profileId + "/finalize").header("Authorization", session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRF_FINALIZE_BLOCKED"));
        // 编辑措辞不改锚点也会保持待确认；确认剩余条目 → 定稿成功 → 撤销定稿回 confirmed
        mvc.perform(post("/api/profile/" + profileId + "/claims/" + secondClaimId + "/confirm")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk());
        MvcResult finalized = mvc.perform(post("/api/profile/" + profileId + "/finalize")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("finalized"))
                .andReturn();
        String detailAfterFinalize = mvc.perform(get("/api/profile/" + profileId)
                        .header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("finalized"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(field(detailAfterFinalize, "finalizedAt")).isNotBlank();
        // 定稿后编辑主张 → 自动降级回 draft、定稿时间清空
        mvc.perform(put("/api/profile/" + profileId + "/claims/" + firstClaimId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "wording", "深入理解 Redis 持久化机制", "anchorIds", List.of(kpId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unconfirmed"));
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.status").value("draft"));
        mvc.perform(post("/api/profile/" + profileId + "/unfinalize").header("Authorization", session.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/profile/" + profileId + "/claims/" + firstClaimId + "/confirm")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/profile/" + profileId + "/finalize").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("finalized"));
        mvc.perform(post("/api/profile/" + profileId + "/unfinalize").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
    }

    // —— 7. 职级缺省推测 ——

    @Test
    void levelSuggestionByYears() throws Exception {
        AuthSession session = registerCustomer();
        mvc.perform(get("/api/profile/level-suggestion?years=1").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("junior"));
        mvc.perform(get("/api/profile/level-suggestion?years=4").header("Authorization", session.token()))
                .andExpect(jsonPath("$.level").value("mid"));
        mvc.perform(get("/api/profile/level-suggestion?years=10").header("Authorization", session.token()))
                .andExpect(jsonPath("$.level").value("expert"));
    }

    // —— 8. C 端 kp 搜索只回 available ——

    @Test
    void kpSearchReturnsAvailableOnly() throws Exception {
        AuthSession session = registerCustomer();
        // 共享测试库里已有其他测试造的 HashMap 知识点，用唯一关键词隔离断言
        String unique = "哲思异构词" + System.nanoTime();
        seedKp(unique + " 持久化机制", "available");
        seedKp(unique + " 加密变体（待审）", "pending");
        mvc.perform(get("/api/kp/search?q=" + unique).header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value(unique + " 持久化机制"))
                .andExpect(jsonPath("$[0].topicName").isNotEmpty());
    }

    // —— 9. 项目素材增改删 ——

    @Test
    void caseCrudFlow() throws Exception {
        AuthSession session = registerCustomer();
        long profileId = firstProfileId(session);
        MvcResult added = mvc.perform(post("/api/profile/" + profileId + "/cases")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "projectName", "交易网关", "background", "日均千万流量",
                                "metrics", "P99 80ms", "conflict", "幂等与顺序性", "result", "平稳上线"))))
                .andExpect(status().isOk()).andReturn();
        long caseId = Long.parseLong(field(added.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        mvc.perform(put("/api/profile/" + profileId + "/cases/" + caseId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "projectName", "交易网关", "background", "日均五千万流量",
                                "metrics", "P99 60ms", "conflict", "幂等", "result", "零事故"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.background").value("日均五千万流量"));
        mvc.perform(delete("/api/profile/" + profileId + "/cases/" + caseId)
                        .header("Authorization", session.token()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.cases.length()").value(0));
    }

    // —— 10. 参数校验 ——

    @Test
    void parameterValidation() throws Exception {
        AuthSession session = registerCustomer();
        long profileId = firstProfileId(session);
        // 简历超长 400
        mvc.perform(put("/api/profile/" + profileId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBodyWithResume("x".repeat(20001))))
                .andExpect(status().isBadRequest());
        // 锚点为空 400
        mvc.perform(post("/api/profile/" + profileId + "/claims")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(
                                Map.of("wording", "熟悉 Redis", "anchorIds", List.of()))))
                .andExpect(status().isBadRequest());
        // 锚点越界 400 PRF_ANCHOR_INVALID
        mvc.perform(post("/api/profile/" + profileId + "/claims")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "wording", "熟悉 Redis", "anchorIds", List.of(424242)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRF_ANCHOR_INVALID"));
        // AI 起草输入全空 400
        mvc.perform(post("/api/profile/" + profileId + "/ai/claims")
                        .header("Authorization", session.token()))
                .andExpect(status().isBadRequest());
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

    private long createProfile(AuthSession session, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/profile").header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk()).andReturn();
        return Long.parseLong(field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
    }

    private long firstProfileId(AuthSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/profile").header("Authorization", session.token()))
                .andExpect(status().isOk()).andReturn();
        return Long.parseLong(field(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
    }

    private long seedKp(String title, String status) {
        Long topicId = kbTopicService.list("java").stream()
                .filter(topic -> "JVM".equals(topic.name())).findFirst().orElseThrow().id();
        KbKp kp = new KbKp();
        kp.setDomainCode("java");
        kp.setTopicId(topicId);
        kp.setTitle(title);
        kp.setSummary(title + " 的标准概述");
        kp.setFrameworkMd("叙述框架正文");
        kp.setDifficulty("mid");
        kp.setStatus(status);
        kp.setCreatedBy(1L);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        kp.setCreatedAt(now);
        kp.setUpdatedAt(now);
        kpMapper.insert(kp);
        return kp.getId();
    }

    private String updateBody(String targetLevel) {
        return new JsonMapper().writeValueAsString(Map.of(
                "name", "我的档案",
                "resumeText", "三年 Java 后端经验",
                "years", 4,
                "position", "Java 后端工程师",
                "jdText", "",
                "techStack", "Java, Redis",
                "targetLevel", targetLevel,
                "planWeeks", 12,
                "dailyMinutes", 60));
    }

    private String updateBodyWithResume(String resume) {
        return new JsonMapper().writeValueAsString(Map.of(
                "name", "我的档案",
                "resumeText", resume,
                "position", "",
                "jdText", "",
                "techStack", "",
                "targetLevel", ""));
    }

    private String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少字段: " + name + " -> " + json);
        }
        return matcher.group(1);
    }
}
