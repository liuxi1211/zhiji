package com.zhiji.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.api.PromptTemplate;
import com.zhiji.profile.domain.PrfIntakeMsg;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.repo.PrfIntakeMsgMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 建档对话服务（Intake Brainstorm，建档 PRD v2 4.4）：头脑风暴式技能条起草——首轮依据
 * 基础信息 + 参考池直接给完整草案，之后用户自然语言反馈、AI 全量修订。
 *
 * 服务端红线（提示词铁律的代码兜底）：
 * - 轮次上限 5（PRF_CHAT_ROUND_LIMIT），超限引导采纳或手写；
 * - 产物条款目校验：小标题 ≤40、正文 ≤300 且非空，不合格条目丢弃；
 * - 小标题归一化查重：同题多条只保留首条（重复是「搭配不均」的主要来源之一）；
 * - 覆盖检查：用户技术栈组件未被草案覆盖的，随轮次返回 coverageGaps（遗漏兜底，前端一键补写）；
 * - 用户输入 ≤2000 字；会话状态持久化，随时退出可回。
 */
@Service
public class IntakeChatService {

    private static final Logger log = LoggerFactory.getLogger(IntakeChatService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 修订轮次上限（PRD v2 4.4.4：超限说明首轮跑偏或用户没想清楚，引导采纳或手写） */
    public static final int MAX_ROUNDS = 5;

    private static final int MAX_USER_TEXT_CHARS = 2_000;

    private static final int MAX_TITLE_CHARS = 40;

    private static final int MAX_CONTENT_CHARS = 300;

    private static final int MAX_REPLY_CHARS = 500;

    /** JSON 任务输出长，放宽到 120s（对齐 SourceAiService 约定） */
    public static final int CHAT_TIMEOUT_SECONDS = 120;

    private final PrfIntakeMsgMapper msgMapper;
    private final LlmClient llmClient;
    private final ReferencePoolService referencePoolService;
    private final JsonMapper jsonMapper = new JsonMapper();

    public IntakeChatService(PrfIntakeMsgMapper msgMapper, LlmClient llmClient,
            ReferencePoolService referencePoolService) {
        this.msgMapper = msgMapper;
        this.llmClient = llmClient;
        this.referencePoolService = referencePoolService;
    }

    // —— 视图契约 ——

    /** 一条技能条：小标题 + 正文（未提供的事实以【】占位） */
    public record ChatClaim(String title, String content) {
    }

    /** 一轮教练输出：reply 为给用户看的一句话说明，claims 为该轮完整版本（空 = 闲聊/提问轮）；
     *  coverageGaps 为用户技术栈中未被草案覆盖的组件（claims 非空时才计算）。 */
    public record ChatTurnView(long round, String reply, List<ChatClaim> claims, List<String> coverageGaps) {
    }

    public record MessageView(long id, String role, String text, List<ChatClaim> claims, String createdAt) {
    }

    public record ChatHistoryView(int round, List<MessageView> messages) {
    }

    record ChatResp(String reply, List<ChatClaim> claims) {
    }

    // —— 历史 ——

    public ChatHistoryView history(PrfProfile profile) {
        List<PrfIntakeMsg> messages = load(profile);
        int round = (int) messages.stream().filter(m -> "assistant".equals(m.getRole())).count();
        return new ChatHistoryView(round, messages.stream().map(this::toView).toList());
    }

    @Transactional
    public void clear(PrfProfile profile) {
        msgMapper.delete(new LambdaQueryWrapper<PrfIntakeMsg>()
                .eq(PrfIntakeMsg::getProfileId, profile.getId()));
    }

    // —— 首轮（进入即出完整草案，不是先访谈后出稿） ——

    @Transactional
    public ChatTurnView firstRound(PrfProfile profile) {
        if (assistantRound(profile.getId()) > 0) {
            throw BizException.conflict("PRF_CHAT_ALREADY_STARTED", "建档对话已开始，请继续修订或直接采纳");
        }
        // 起草依据门槛（PRD v2 4.2：必填只有年限与技术栈，没填不准开聊）
        if (profile.getYears() == null || nz(profile.getTechStack()).isEmpty()) {
            throw BizException.of(400, "PRF_INTAKE_NOT_READY", "请先在基础信息中填写工作年限与技术栈，再开始对话");
        }
        String system = renderSystem(profile);
        String user = renderTemplate("prompts/intake_first.txt",
                Map.of("userInfo", userInfo(profile)));
        store(profile, "user", "请根据我的基础信息生成第一版技能条", null);
        return callAndStore(profile, system, user);
    }

    // —— 修订轮 ——

    @Transactional
    public ChatTurnView revise(PrfProfile profile, String userText) {
        String text = requireText(userText);
        List<PrfIntakeMsg> history = load(profile);
        long rounds = history.stream().filter(m -> "assistant".equals(m.getRole())).count();
        if (rounds >= MAX_ROUNDS) {
            throw BizException.of(400, "PRF_CHAT_ROUND_LIMIT",
                    "对话最多 " + MAX_ROUNDS + " 轮，请直接采纳当前版本或手动编辑技能条");
        }
        String system = renderSystem(profile);
        String user = transcript(history) + "\n【用户本轮反馈】\n" + text + "\n\n请按修订协议输出。";
        store(profile, "user", text, null);
        return callAndStore(profile, system, user);
    }

    // —— 内部 ——

    private ChatTurnView callAndStore(PrfProfile profile, String system, String user) {
        ChatResp resp = llmClient.completeJson(LlmRequest
                .of(LlmPurpose.PROFILE_DRAFT, profile.getId(), user)
                .systemPrompt(system)
                .timeoutSeconds(CHAT_TIMEOUT_SECONDS)
                .build(), ChatResp.class);
        List<ChatClaim> claims = normalize(resp.claims() == null ? List.<ChatClaim>of() : resp.claims());
        claims = dedupeTitles(claims);
        String reply = resp.reply() == null ? "" : resp.reply().strip();
        if (reply.length() > MAX_REPLY_CHARS) {
            reply = reply.substring(0, MAX_REPLY_CHARS);
        }
        List<String> gaps = claims.isEmpty() ? List.of() : coverageGaps(profile.getTechStack(), claims);
        if (!gaps.isEmpty()) {
            log.info("技能条草案覆盖缺口：{}（profileId={}）", gaps, profile.getId());
        }
        store(profile, "assistant", reply, claims.isEmpty() ? null : jsonMapper.writeValueAsString(claims));
        int round = (int) assistantRound(profile.getId());
        return new ChatTurnView(round, reply, claims, gaps);
    }

    /** 条目归一：标题缺省补"能力方向 N"，正文去空/截断/去重——不合格条目丢弃并留痕。 */
    private List<ChatClaim> normalize(List<ChatClaim> raw) {
        List<ChatClaim> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 1;
        for (ChatClaim claim : raw) {
            String title = claim.title() == null ? "" : claim.title().strip();
            String content = claim.content() == null ? "" : claim.content().strip();
            if (content.isEmpty()) {
                log.info("丢弃空正文技能条草稿");
                continue;
            }
            if (content.length() > MAX_CONTENT_CHARS) {
                log.info("丢弃超长技能条草稿：{} 字", content.length());
                continue;
            }
            if (title.isEmpty()) {
                title = "能力方向 " + index;
            }
            if (title.length() > MAX_TITLE_CHARS) {
                title = title.substring(0, MAX_TITLE_CHARS);
            }
            if (seen.add(title + "|" + content)) {
                result.add(new ChatClaim(title, content));
            }
            index++;
        }
        return result;
    }

    /** 小标题归一化查重（去空白、大小写与中英文标点后比较）：同题多条只保留首条，其余丢弃留痕。 */
    private List<ChatClaim> dedupeTitles(List<ChatClaim> claims) {
        List<ChatClaim> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChatClaim claim : claims) {
            String key = claim.title().toLowerCase()
                    .replaceAll("[\\s\\p{Punct}\\u3000、，。：；！？（）【】《》「」『』—…·\\-]", "");
            if (key.isEmpty() || seen.add(key)) {
                result.add(claim);
            } else {
                log.info("丢弃小标题重复的技能条草稿：{}", claim.title());
            }
        }
        return result;
    }

    /**
     * 覆盖检查（遗漏兜底）：用户技术栈中未被任何草案条目覆盖的组件。
     * 匹配规则 = 双方归一化（小写、去空格与连字符）后包含判断；纯提示性质，前端展示并可一键补写。
     */
    static List<String> coverageGaps(String techStack, List<ChatClaim> claims) {
        List<String> tokens = parseTechStack(techStack);
        if (tokens.isEmpty()) {
            return List.of();
        }
        StringBuilder all = new StringBuilder();
        for (ChatClaim claim : claims) {
            all.append(claim.title()).append(' ').append(claim.content()).append(' ');
        }
        String haystack = normalizeToken(all.toString());
        List<String> gaps = new ArrayList<>();
        for (String token : tokens) {
            if (!haystack.contains(normalizeToken(token))) {
                gaps.add(token);
            }
        }
        return gaps;
    }

    /** 技术栈自由文本 → 组件列表：按常见分隔符切分、去空去重、保留 2~30 字的有效项。 */
    static List<String> parseTechStack(String techStack) {
        if (techStack == null || techStack.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : techStack.split("[,，、;；|/\\n]")) {
            String token = part.strip();
            if (token.length() >= 2 && token.length() <= 30) {
                tokens.add(token);
            }
        }
        return new ArrayList<>(tokens);
    }

    private static String normalizeToken(String value) {
        return value.toLowerCase().replace(" ", "").replace("-", "");
    }

    private String userInfo(PrfProfile profile) {
        StringBuilder builder = new StringBuilder();
        builder.append("工作年限：").append(profile.getYears() == null ? "未填写" : profile.getYears() + " 年").append('\n');
        builder.append("技术栈：").append(nz(profile.getTechStack()).isEmpty() ? "未填写" : nz(profile.getTechStack())).append('\n');
        builder.append("当前岗位描述：").append(nz(profile.getPosition()).isEmpty() ? "未填写" : nz(profile.getPosition())).append('\n');
        builder.append("目标岗位 / JD：").append(nz(profile.getJdText()).isEmpty() ? "未填写" : nz(profile.getJdText())).append('\n');
        builder.append("想突出的 / 擅长的：").append(nz(profile.getGoodAt()).isEmpty() ? "未填写" : nz(profile.getGoodAt())).append('\n');
        String resume = nz(profile.getResumeText());
        builder.append("简历原文：").append(resume.isEmpty() ? "未提供（用户没有简历，依据上面对话与信息起草）" : truncate(resume));
        return builder.toString();
    }

    private String transcript(List<PrfIntakeMsg> messages) {
        StringBuilder builder = new StringBuilder();
        builder.append("【之前的对话】\n");
        for (PrfIntakeMsg message : messages) {
            if ("user".equals(message.getRole())) {
                builder.append("用户：").append(message.getText()).append('\n');
            } else {
                builder.append("教练：").append(message.getClaimsJson() == null || message.getClaimsJson().isBlank()
                        ? message.getText()
                        : message.getClaimsJson()).append('\n');
            }
        }
        return builder.toString();
    }

    private String renderSystem(PrfProfile profile) {
        return renderTemplate("prompts/intake_system.txt", Map.of(
                "referencePool", referencePoolService.renderBars(),
                "sectionExemplars", referencePoolService.renderSections(profile)));
    }

    private List<PrfIntakeMsg> load(PrfProfile profile) {
        return msgMapper.selectList(new LambdaQueryWrapper<PrfIntakeMsg>()
                .eq(PrfIntakeMsg::getProfileId, profile.getId())
                .orderByAsc(PrfIntakeMsg::getId));
    }

    private long assistantRound(long profileId) {
        return msgMapper.selectCount(new LambdaQueryWrapper<PrfIntakeMsg>()
                .eq(PrfIntakeMsg::getProfileId, profileId)
                .eq(PrfIntakeMsg::getRole, "assistant"));
    }

    private void store(PrfProfile profile, String role, String text, String claimsJson) {
        PrfIntakeMsg message = new PrfIntakeMsg();
        message.setProfileId(profile.getId());
        message.setRole(role);
        message.setText(text == null ? "" : text);
        message.setClaimsJson(claimsJson);
        message.setCreatedAt(LocalDateTime.now().format(TS));
        msgMapper.insert(message);
    }

    private MessageView toView(PrfIntakeMsg message) {
        List<ChatClaim> claims = List.of();
        if (message.getClaimsJson() != null && !message.getClaimsJson().isBlank()) {
            claims = jsonMapper.readValue(message.getClaimsJson(),
                    jsonMapper.getTypeFactory().constructCollectionType(List.class, ChatClaim.class));
        }
        return new MessageView(message.getId(), message.getRole(), message.getText(), claims, message.getCreatedAt());
    }

    private String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw BizException.validation("反馈内容不能为空");
        }
        String value = text.strip();
        if (value.length() > MAX_USER_TEXT_CHARS) {
            throw BizException.validation("反馈内容长度 " + MAX_USER_TEXT_CHARS + " 字以内");
        }
        return value;
    }

    private String truncate(String text) {
        String stripped = text.strip();
        return stripped.length() > 12_000 ? stripped.substring(0, 12_000) + "\n\n（超长截断）" : stripped;
    }

    private String nz(String value) {
        return value == null ? "" : value.strip();
    }

    private String renderTemplate(String path, Map<String, ?> vars) {
        String template = readTemplate(path);
        return PromptTemplate.render(template, vars);
    }

    private String readTemplate(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少 prompt 模板: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 模板失败: " + path, e);
        }
    }
}
