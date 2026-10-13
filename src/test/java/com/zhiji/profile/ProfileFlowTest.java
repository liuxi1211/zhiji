package com.zhiji.profile;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 个人档案链路 · 对话流（建档 PRD v2）：注册即建默认档案、3 份上限、当前档案切换、
 * 对话头脑风暴（首轮即出草案 → 自然语言修订 → 整版采纳即拍板）、锚点越界剔除、
 * 占位清零定稿门槛、简历 Markdown 导出、定稿后锚定映射、越权防护。
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
        mvc.perform(get("/api/profile/" + profileId + "/chat").header("Authorization", stranger.token()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/profile/" + profileId + "/chat/first").header("Authorization", stranger.token()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/profile/" + profileId + "/finalize").header("Authorization", stranger.token()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/profile/" + profileId).header("Authorization", stranger.token()))
                .andExpect(status().isNotFound());
    }

    // —— 4b. 删除档案：连带清理子数据、当前档案顶替、上限空位回收 ——

    @Test
    void deleteProfileCascadesAndPromotesNext() throws Exception {
        AuthSession session = registerCustomer();
        long firstId = firstProfileId(session);
        long secondId = createProfile(session, "Go 方向");
        mvc.perform(post("/api/profile/" + secondId + "/activate").header("Authorization", session.token()))
                .andExpect(status().isOk());
        // 第一份档案留些子数据（采纳版技能条 + 项目素材 + 对话消息）
        mvc.perform(put("/api/profile/" + firstId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("mid")))
                .andExpect(status().isOk());
        String acceptBody = new JsonMapper().writeValueAsString(Map.of("claims", List.of(
                Map.of("title", "Java 基础", "content", "3 年 Java 后端研发经验，熟练使用 Spring Boot。"))));
        mvc.perform(post("/api/profile/" + firstId + "/claims/accept")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody))
                .andExpect(status().isOk());
        mvc.perform(post("/api/profile/" + firstId + "/cases")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "projectName", "交易网关", "background", "日均千万流量"))))
                .andExpect(status().isOk());
        stub.respond("{\"reply\":\"第一版草案来了\",\"claims\":[{\"title\":\"Java 基础\",\"content\":\"草案。\"}]}");
        mvc.perform(post("/api/profile/" + firstId + "/chat/first").header("Authorization", session.token()))
                .andExpect(status().isOk());
        // 删除当前档案（secondId 为当前）→ 剩余的第一份自动顶为当前
        mvc.perform(delete("/api/profile/" + secondId).header("Authorization", session.token()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/profile").header("Authorization", session.token()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) firstId))
                .andExpect(jsonPath("$[0].active").value(true));
        // 第一份档案数据完好
        mvc.perform(get("/api/profile/" + firstId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.claims.length()").value(1))
                .andExpect(jsonPath("$.cases.length()").value(1));
        // 删除第一份（含子数据）→ 列表为空；上限空位回收，可再建
        mvc.perform(delete("/api/profile/" + firstId).header("Authorization", session.token()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/profile").header("Authorization", session.token()))
                .andExpect(jsonPath("$.length()").value(0));
        createProfile(session, "重建档案");
    }

    // —— 5. 对话头脑风暴：首轮草案落库、重复首轮被拒、修订全量替换 ——

    @Test
    void intakeChatFirstRoundAndRevise() throws Exception {
        AuthSession session = registerCustomer();
        long profileId = firstProfileId(session);
        mvc.perform(put("/api/profile/" + profileId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("mid")))
                .andExpect(status().isOk());
        stub.respond("{\"reply\":\"第一版草案来了\",\"claims\":["
                + "{\"title\":\"存储与缓存\",\"content\":\"熟练使用 MySQL 进行索引设计，理解事务与 MVCC。\"},"
                + "{\"title\":\"\",\"content\":\"使用 Redis 承担缓存场景，理解穿透与雪崩治理。\"}]}");
        mvc.perform(post("/api/profile/" + profileId + "/chat/first")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(1))
                .andExpect(jsonPath("$.reply").value("第一版草案来了"))
                .andExpect(jsonPath("$.claims.length()").value(2))
                .andExpect(jsonPath("$.claims[0].title").value("存储与缓存"));
        // 草案不落技能条，落对话消息；重复首轮被拒
        mvc.perform(get("/api/profile/" + profileId + "/chat").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(1))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].claims.length()").value(2));
        mvc.perform(post("/api/profile/" + profileId + "/chat/first").header("Authorization", session.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRF_CHAT_ALREADY_STARTED"));
        // 修订：带占位的新版本；空标题自动补缺省
        stub.respond("{\"reply\":\"已按你的反馈调整\",\"claims\":["
                + "{\"title\":\"存储与分库分表\",\"content\":\"主导订单库分库分表改造，拆分为 32 个分片。\"},"
                + "{\"title\":\"消息中间件\",\"content\":\"使用 RocketMQ 承担异步解耦，具备【线上消息问题排查经历待补充】。\"}]}");
        mvc.perform(post("/api/profile/" + profileId + "/chat/revise")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "text", "我主导过分库分表，拆了 32 个分片；补一条消息中间件"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(2))
                .andExpect(jsonPath("$.claims.length()").value(2))
                .andExpect(jsonPath("$.claims[1].content").value(
                        org.hamcrest.Matchers.containsString("【线上消息问题排查经历待补充】")));
        mvc.perform(get("/api/profile/" + profileId + "/chat").header("Authorization", session.token()))
                .andExpect(jsonPath("$.round").value(2))
                .andExpect(jsonPath("$.messages.length()").value(4));
        // 技能条仍未落库，等"就用这版"
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.claims.length()").value(0));
    }

    // —— 6. 整版采纳即拍板；占位清零定稿门槛；导出 Markdown ——

    @Test
    void acceptVersionPlaceholderGateAndExport() throws Exception {
        AuthSession session = registerCustomer();
        long profileId = firstProfileId(session);
        mvc.perform(put("/api/profile/" + profileId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("mid")))
                .andExpect(status().isOk());
        String acceptBody = new JsonMapper().writeValueAsString(Map.of("claims", List.of(
                Map.of("title", "存储与分库分表", "content", "主导订单库分库分表改造，拆分为 32 个分片。"),
                Map.of("title", "消息中间件", "content", "使用 RocketMQ 承担异步解耦，具备【线上问题排查经历待补充】。"))));
        MvcResult accepted = mvc.perform(post("/api/profile/" + profileId + "/claims/accept")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("confirmed"))
                .andExpect(jsonPath("$[0].anchorStatus").value("unmapped"))
                .andExpect(jsonPath("$[0].anchorIds.length()").value(0))
                .andReturn();
        // 采纳即拍板 → 档案自动 confirmed
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.claims.length()").value(2));
        // 有未处理占位 → 定稿被拒
        mvc.perform(post("/api/profile/" + profileId + "/finalize").header("Authorization", session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRF_PLACEHOLDER_UNRESOLVED"));
        // 导出同样被占位拦住
        mvc.perform(get("/api/profile/" + profileId + "/export").header("Authorization", session.token()))
                .andExpect(status().isBadRequest());
        // 填写占位（编辑措辞，不回退拍板状态）→ 定稿成功 → 导出含小标题
        long secondClaimId = secondIdOf(accepted.getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(put("/api/profile/" + profileId + "/claims/" + secondClaimId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "title", "消息中间件",
                                "wording", "使用 RocketMQ 承担异步解耦，主导过 2 次积压线上处置。"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        mvc.perform(get("/api/profile/" + profileId + "/export").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value(
                        org.hamcrest.Matchers.containsString("## 个人能力")))
                .andExpect(jsonPath("$.markdown").value(
                        org.hamcrest.Matchers.containsString("**存储与分库分表**")))
                .andExpect(jsonPath("$.markdown").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("【"))));
        mvc.perform(post("/api/profile/" + profileId + "/finalize").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("finalized"));
    }

    // —— 7. 定稿后锚定映射：越界剔除、置信度落状态 ——

    @Test
    void anchorMappingAfterFinalize() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = seedKp("Redis 持久化机制与大 key 治理", "available");
        long profileId = firstProfileId(session);
        mvc.perform(put("/api/profile/" + profileId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("mid")))
                .andExpect(status().isOk());
        String acceptBody = new JsonMapper().writeValueAsString(Map.of("claims", List.of(
                Map.of("title", "缓存与 Redis", "content", "熟练使用 Redis 承担缓存场景，理解持久化机制与大 key 治理。"),
                Map.of("title", "量子计算", "content", "了解量子计算在调度问题上的应用。"))));
        String accepted = mvc.perform(post("/api/profile/" + profileId + "/claims/accept")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long firstClaimId = firstIdOf(accepted);
        long secondClaimId = secondIdOf(accepted);
        // 模型给：第一条 high 命中真实 kp；第二条 none（且夹带越界 id，应被剔除）
        stub.respond("{\"mappings\":["
                + "{\"claimId\":" + firstClaimId + ",\"anchorKpIds\":[" + kpId + "],\"confidence\":\"high\"},"
                + "{\"claimId\":" + secondClaimId + ",\"anchorKpIds\":[999999],\"confidence\":\"low\"}]}");
        mvc.perform(post("/api/profile/" + profileId + "/anchors/map")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapped").value(1))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.zero").value(1));
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.claims[0].anchorStatus").value("mapped"))
                .andExpect(jsonPath("$.claims[0].anchors[0].id").value((int) kpId))
                .andExpect(jsonPath("$.claims[1].anchorStatus").value("zero"))
                .andExpect(jsonPath("$.claims[1].anchorIds.length()").value(0));
        // 已 mapped 的条目不重复映射
        stub.respond("{\"mappings\":[{\"claimId\":" + secondClaimId + ",\"anchorKpIds\":[" + kpId + "],\"confidence\":\"high\"}]}");
        mvc.perform(post("/api/profile/" + profileId + "/anchors/map").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapped").value(0));
    }

    // —— 8. 对话轮次上限 5 ——

    @Test
    void intakeChatRoundLimit() throws Exception {
        AuthSession session = registerCustomer();
        long profileId = firstProfileId(session);
        mvc.perform(put("/api/profile/" + profileId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("mid")))
                .andExpect(status().isOk());
        stub.respond("{\"reply\":\"ok\",\"claims\":[{\"title\":\"Java 基础\",\"content\":\"3 年 Java 后端研发经验。\"}]}");
        mvc.perform(post("/api/profile/" + profileId + "/chat/first").header("Authorization", session.token()))
                .andExpect(status().isOk());
        // 轮次口径：首轮即第 1 轮，之后最多修 4 轮，第 5 次修订（= 第 6 轮）被拒
        for (int i = 0; i < 4; i++) {
            stub.respond("{\"reply\":\"ok\",\"claims\":[{\"title\":\"Java 基础\",\"content\":\"3 年 Java 后端研发经验，第 "
                    + (i + 1) + " 轮。\"}]}");
            mvc.perform(post("/api/profile/" + profileId + "/chat/revise")
                            .header("Authorization", session.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new JsonMapper().writeValueAsString(Map.of("text", "再改一版 " + i))))
                    .andExpect(status().isOk());
        }
        stub.respond("{\"reply\":\"ok\",\"claims\":[]}");
        mvc.perform(post("/api/profile/" + profileId + "/chat/revise")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("text", "第 6 轮"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRF_CHAT_ROUND_LIMIT"));
        // 清空对话可重新开始
        mvc.perform(delete("/api/profile/" + profileId + "/chat").header("Authorization", session.token()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/profile/" + profileId + "/chat").header("Authorization", session.token()))
                .andExpect(jsonPath("$.round").value(0))
                .andExpect(jsonPath("$.messages.length()").value(0));
        stub.respond("{\"reply\":\"第一版草案来了\",\"claims\":[{\"title\":\"Java 基础\",\"content\":\"重新起草。\"}]}");
        mvc.perform(post("/api/profile/" + profileId + "/chat/first").header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(1));
    }

    // —— 9. 手动加条与状态机（对话流下：手动条仍走未确认 → 拍板） ——

    @Test
    void manualClaimStateMachine() throws Exception {
        AuthSession session = registerCustomer();
        long kpId = seedKp("Redis 持久化机制", "available");
        long profileId = firstProfileId(session);
        MvcResult added = mvc.perform(post("/api/profile/" + profileId + "/claims")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "title", "存储与缓存", "wording", "熟悉 Redis 持久化机制",
                                "anchorIds", List.of(kpId), "source", "manual"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unconfirmed"))
                .andExpect(jsonPath("$.anchorStatus").value("mapped"))
                .andExpect(jsonPath("$.anchors[0].id").value((int) kpId))
                .andReturn();
        long claimId = Long.parseLong(
                field(added.getResponse().getContentAsString(StandardCharsets.UTF_8), "id"));
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.status").value("draft"));
        // 编辑措辞不回退状态（占位填写不该撤销确认），锚点保留
        mvc.perform(put("/api/profile/" + profileId + "/claims/" + claimId)
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "title", "存储与缓存", "wording", "深入理解 Redis 持久化机制"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unconfirmed"))
                .andExpect(jsonPath("$.anchorStatus").value("mapped"));
        mvc.perform(post("/api/profile/" + profileId + "/claims/" + claimId + "/confirm")
                        .header("Authorization", session.token()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.status").value("confirmed"));
        // 采纳整版时手动条目保留
        String acceptBody = new JsonMapper().writeValueAsString(Map.of("claims", List.of(
                Map.of("title", "Java 基础", "content", "3 年 Java 后端研发经验，熟练使用 Spring Boot。"))));
        mvc.perform(post("/api/profile/" + profileId + "/claims/accept")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody))
                .andExpect(status().isOk());
        mvc.perform(get("/api/profile/" + profileId).header("Authorization", session.token()))
                .andExpect(jsonPath("$.claims.length()").value(2))
                .andExpect(jsonPath("$.claims[0].source").value("manual"));
    }

    // —— 10. 职级缺省推测 ——

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

    // —— 11. C 端 kp 搜索只回 available ——

    @Test
    void kpSearchReturnsAvailableOnly() throws Exception {
        AuthSession session = registerCustomer();
        String unique = "哲思异构词" + System.nanoTime();
        seedKp(unique + " 持久化机制", "available");
        seedKp(unique + " 加密变体（待审）", "pending");
        mvc.perform(get("/api/kp/search?q=" + unique).header("Authorization", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value(unique + " 持久化机制"))
                .andExpect(jsonPath("$[0].topicName").isNotEmpty());
    }

    // —— 12. 项目素材增改删 ——

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

    // —— 13. 参数校验 ——

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
        // 反馈空白 400
        mvc.perform(post("/api/profile/" + profileId + "/chat/revise")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("text", "  "))))
                .andExpect(status().isBadRequest());
        // 采纳空版本 400
        mvc.perform(post("/api/profile/" + profileId + "/claims/accept")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of("claims", List.of()))))
                .andExpect(status().isBadRequest());
        // 锚点越界 400 PRF_ANCHOR_INVALID
        mvc.perform(post("/api/profile/" + profileId + "/claims")
                        .header("Authorization", session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new JsonMapper().writeValueAsString(Map.of(
                                "title", "缓存", "wording", "熟悉 Redis", "anchorIds", List.of(424242)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRF_ANCHOR_INVALID"));
        // 基础信息未完成（年限/技术栈为空）→ 对话首轮被拒
        mvc.perform(post("/api/profile/" + profileId + "/chat/first")
                        .header("Authorization", session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRF_INTAKE_NOT_READY"));
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

    /** 数组响应里的第 1 个 id（采纳版本 = 第 1 条技能条）。 */
    private long firstIdOf(String arrayJson) {
        var matcher = Pattern.compile("\"id\":(\\d+)").matcher(arrayJson);
        if (!matcher.find()) {
            throw new IllegalStateException("响应缺少 id: " + arrayJson);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** 数组响应里的第 2 个 id（采纳版本 = 第 2 条技能条）。 */
    private long secondIdOf(String arrayJson) {
        var matcher = Pattern.compile("\"id\":(\\d+)").matcher(arrayJson);
        if (!matcher.find() || !matcher.find()) {
            throw new IllegalStateException("响应缺少第二个 id: " + arrayJson);
        }
        return Long.parseLong(matcher.group(1));
    }
}
