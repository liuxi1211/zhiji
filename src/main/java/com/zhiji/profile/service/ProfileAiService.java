package com.zhiji.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.KbContentStatus;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.api.PromptTemplate;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.service.ProfileService.AnchorView;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 建档 AI 辅助（核心模块定义 4.1/6.4）：从简历文本 + 补齐表单起草技能条。
 * AI 只做选择与组织：锚点候选先由代码从题库捞出（available），模型只能从中挑选，
 * 代码再逐 id 复核——锚点越界或空的草稿条目直接丢弃，不允许模型编造考点。
 * 产物不落库，回前端草稿区逐条采纳（用不用由人）。
 */
@Service
public class ProfileAiService {

    private static final Logger log = LoggerFactory.getLogger(ProfileAiService.class);

    /** 锚点候选上限：喂给模型的候选清单规模 */
    static final int MAX_CANDIDATES = 50;

    private static final int MAX_RESUME_CHARS = 12_000;

    /** JSON 任务输出长，统一放宽到 120s（对齐 SourceAiService 约定） */
    public static final int DRAFT_TIMEOUT_SECONDS = 120;

    private final LlmClient llmClient;
    private final KbKpMapper kpMapper;
    private final JsonMapper jsonMapper = new JsonMapper();

    public ProfileAiService(LlmClient llmClient, KbKpMapper kpMapper) {
        this.llmClient = llmClient;
        this.kpMapper = kpMapper;
    }

    // —— 视图契约 ——

    public record ClaimDraftView(String wording, List<AnchorView> anchors) {
    }

    record DraftResp(List<DraftClaim> claims) {
    }

    record DraftClaim(String wording, List<Long> anchorKpIds) {
    }

    /**
     * 起草技能条：输入 = 档案的简历全文 + 补齐表单；输出 = 建议列表（不落库）。
     * 含非法锚点、零锚点或空白措辞的条目直接丢弃（留痕日志），全部无效时返回空列表。
     */
    public List<ClaimDraftView> draftClaims(PrfProfile profile) {
        String resume = nz(profile.getResumeText());
        String stack = nz(profile.getTechStack());
        String position = nz(profile.getPosition());
        if (resume.isEmpty() && stack.isEmpty() && position.isEmpty()) {
            throw BizException.validation("简历与技术栈信息均为空，请先粘贴简历或填写技术栈");
        }
        List<KbKp> candidates = findCandidates(profile);
        if (candidates.isEmpty()) {
            throw BizException.validation("题库中暂无已上线知识点，无法起草技能条");
        }
        String prompt = prompt("prompts/profile_claims.txt", Map.of(
                "position", position.isEmpty() ? "未填写" : position,
                "years", profile.getYears() == null ? "未填写" : profile.getYears() + " 年",
                "techStack", stack.isEmpty() ? "未填写" : stack,
                "resumeText", truncate(resume),
                "kpCandidates", renderCandidates(candidates)));
        DraftResp resp = llmClient.completeJson(LlmRequest
                .user(LlmPurpose.PROFILE_DRAFT, profile.getId(), prompt)
                .timeoutSeconds(DRAFT_TIMEOUT_SECONDS)
                .build(), DraftResp.class);
        Map<Long, KbKp> candidateMap = candidates.stream()
                .collect(Collectors.toMap(KbKp::getId, kp -> kp));
        List<ClaimDraftView> result = new ArrayList<>();
        Set<String> seenWordings = new HashSet<>();
        for (DraftClaim claim : resp.claims() == null ? List.<DraftClaim>of() : resp.claims()) {
            String wording = claim.wording() == null ? "" : claim.wording().strip();
            if (wording.isEmpty() || wording.length() > 200
                    || claim.anchorKpIds() == null || claim.anchorKpIds().isEmpty()) {
                log.info("丢弃无效技能条草稿：空白/超长措辞或零锚点");
                continue;
            }
            List<AnchorView> anchors = new ArrayList<>();
            boolean valid = true;
            Set<Long> seenIds = new HashSet<>();
            for (Long id : claim.anchorKpIds()) {
                KbKp kp = id == null ? null : candidateMap.get(id);
                if (kp == null) {
                    valid = false;
                    break;
                }
                if (seenIds.add(kp.getId())) {
                    anchors.add(new AnchorView(kp.getId(), kp.getTitle()));
                }
            }
            if (!valid || anchors.isEmpty()) {
                // 锚点越界 = 模型编造考点，整条拒绝（4.1：对不上的内容直接拒绝）
                log.info("丢弃锚点越界的技能条草稿：{}", wording);
                continue;
            }
            if (seenWordings.add(wording)) {
                result.add(new ClaimDraftView(wording, anchors));
            }
        }
        return result;
    }

    // —— 内部 ——

    /**
     * 锚点候选：技术栈/岗位关键词逐个 LIKE 搜 available kp，不足时用高频知识点补位——
     * 保证候选非空且覆盖主干；候选规模封顶 MAX_CANDIDATES 控制prompt体量。
     */
    private List<KbKp> findCandidates(PrfProfile profile) {
        LinkedHashSet<KbKp> found = new LinkedHashSet<>();
        for (String keyword : keywords(profile)) {
            if (found.size() >= MAX_CANDIDATES) {
                break;
            }
            String like = "%" + keyword + "%";
            found.addAll(kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                    .eq(KbKp::getStatus, KbContentStatus.AVAILABLE.getCode())
                    .and(w -> w.like(KbKp::getTitle, like).or().like(KbKp::getSummary, like))));
        }
        if (found.size() < MAX_CANDIDATES) {
            found.addAll(kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                    .eq(KbKp::getStatus, KbContentStatus.AVAILABLE.getCode())
                    .orderByDesc(KbKp::getFreqManual)
                    .last("LIMIT " + MAX_CANDIDATES)));
        }
        return found.stream().limit(MAX_CANDIDATES).toList();
    }

    private List<String> keywords(PrfProfile profile) {
        String joined = (nz(profile.getTechStack()) + " " + nz(profile.getPosition())).strip();
        if (joined.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(joined.split("[,，、;；/|\\s]+"))
                .map(String::strip)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(10)
                .toList();
    }

    private String renderCandidates(List<KbKp> candidates) {
        StringBuilder builder = new StringBuilder();
        for (KbKp kp : candidates) {
            builder.append(kp.getId()).append(". ").append(kp.getTitle());
            if (kp.getSummary() != null && !kp.getSummary().isBlank()) {
                builder.append(" — ").append(truncateLine(kp.getSummary()));
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private String truncate(String text) {
        String stripped = text.strip();
        return stripped.length() > MAX_RESUME_CHARS
                ? stripped.substring(0, MAX_RESUME_CHARS) + "\n\n（超长截断）"
                : stripped;
    }

    private String truncateLine(String text) {
        String stripped = text.strip().replaceAll("\\s+", " ");
        return stripped.length() > 60 ? stripped.substring(0, 60) + "…" : stripped;
    }

    private String nz(String value) {
        return value == null ? "" : value.strip();
    }

    private String prompt(String path, Map<String, ?> vars) {
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
