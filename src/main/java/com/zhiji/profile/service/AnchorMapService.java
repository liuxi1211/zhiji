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
import com.zhiji.profile.domain.PrfClaim;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.repo.PrfClaimMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 锚定映射（建档 PRD v2 4.6，定稿后执行）：把自然语言的技能条语义映射到题库知识点。
 * AI 只做选择：候选先由代码按技能条关键词从题库捞出（available），模型只能从中挑选并给置信度，
 * 代码逐 id 复核——越界 id 直接剔除。high → mapped；low → pending（待用户在总览确认）；
 * none / 空 → zero（三选一处理，记题库缺口信号）。已 mapped 的条目不重复映射。
 */
@Service
public class AnchorMapService {

    private static final Logger log = LoggerFactory.getLogger(AnchorMapService.class);

    /** 喂给模型的候选清单规模 */
    static final int MAX_CANDIDATES = 60;

    private static final int TIMEOUT_SECONDS = 120;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PrfClaimMapper claimMapper;
    private final KbKpMapper kpMapper;
    private final LlmClient llmClient;
    private final JsonMapper jsonMapper = new JsonMapper();

    public AnchorMapService(PrfClaimMapper claimMapper, KbKpMapper kpMapper, LlmClient llmClient) {
        this.claimMapper = claimMapper;
        this.kpMapper = kpMapper;
        this.llmClient = llmClient;
    }

    // —— 视图契约 ——

    public record MapResult(int mapped, int pending, int zero) {
    }

    record Mapping(Long claimId, List<Long> anchorKpIds, String confidence) {
    }

    record MapResp(List<Mapping> mappings) {
    }

    // —— 映射 ——

    @Transactional
    public MapResult mapAnchors(PrfProfile profile) {
        List<PrfClaim> targets = claimMapper.selectList(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profile.getId())
                .in(PrfClaim::getAnchorStatus, List.of("unmapped", "zero", "pending")));
        if (targets.isEmpty()) {
            return new MapResult(0, 0, 0);
        }
        List<KbKp> candidates = findCandidates(targets);
        if (candidates.isEmpty()) {
            throw BizException.validation("题库中暂无已上线知识点，无法锚定");
        }
        Map<Long, KbKp> candidateMap = candidates.stream()
                .collect(Collectors.toMap(KbKp::getId, kp -> kp, (a, b) -> a));
        MapResp resp = llmClient.completeJson(LlmRequest
                .of(LlmPurpose.PROFILE_DRAFT, profile.getId(), renderUser(targets, candidates))
                .timeoutSeconds(TIMEOUT_SECONDS)
                .build(), MapResp.class);
        Map<Long, PrfClaim> targetMap = targets.stream()
                .collect(Collectors.toMap(PrfClaim::getId, claim -> claim));
        int mapped = 0;
        int pending = 0;
        int zero = 0;
        Set<Long> seenClaims = new HashSet<>();
        for (Mapping mapping : resp.mappings() == null ? List.<Mapping>of() : resp.mappings()) {
            PrfClaim claim = mapping.claimId() == null ? null : targetMap.get(mapping.claimId());
            if (claim == null || !seenClaims.add(claim.getId())) {
                continue;
            }
            List<Long> validIds = mapping.anchorKpIds() == null ? List.of()
                    : mapping.anchorKpIds().stream()
                            .filter(id -> id != null && candidateMap.containsKey(id))
                            .distinct()
                            .toList();
            boolean high = "high".equalsIgnoreCase(mapping.confidence());
            if (validIds.isEmpty() || (!high && !"low".equalsIgnoreCase(mapping.confidence()))) {
                claim.setAnchorIds("[]");
                claim.setAnchorStatus("zero");
                zero++;
            } else {
                claim.setAnchorIds(jsonMapper.writeValueAsString(validIds));
                claim.setAnchorStatus(high ? "mapped" : "pending");
                if (high) {
                    mapped++;
                } else {
                    pending++;
                }
            }
            claim.setUpdatedAt(LocalDateTime.now().format(TS));
            claimMapper.updateById(claim);
        }
        log.info("锚定映射完成 profile={}：mapped={} pending={} zero={}", profile.getId(), mapped, pending, zero);
        return new MapResult(mapped, pending, zero);
    }

    // —— 内部 ——

    /**
     * 候选池：逐条技能条的关键词 LIKE 搜 available kp，合并去重；不足时用高频知识点补位。
     * 候选封顶 MAX_CANDIDATES 控制 prompt 体量。
     */
    private List<KbKp> findCandidates(List<PrfClaim> claims) {
        LinkedHashSet<KbKp> found = new LinkedHashSet<>();
        for (PrfClaim claim : claims) {
            for (String keyword : keywords(claim.getTitle() + " " + claim.getWording())) {
                if (found.size() >= MAX_CANDIDATES) {
                    break;
                }
                String like = "%" + keyword + "%";
                found.addAll(kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                        .eq(KbKp::getStatus, KbContentStatus.AVAILABLE.getCode())
                        .and(w -> w.like(KbKp::getTitle, like).or().like(KbKp::getSummary, like))));
            }
            if (found.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        if (found.size() < MAX_CANDIDATES) {
            found.addAll(kpMapper.selectList(new LambdaQueryWrapper<KbKp>()
                    .eq(KbKp::getStatus, KbContentStatus.AVAILABLE.getCode())
                    .orderByDesc(KbKp::getFreqManual)
                    .last("LIMIT " + MAX_CANDIDATES)));
        }
        return found.stream().limit(MAX_CANDIDATES).toList();
    }

    private List<String> keywords(String text) {
        String joined = text == null ? "" : text.strip();
        if (joined.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(joined.split("[,，、;；/|\\s【】（）()]+"))
                .map(String::strip)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(12)
                .toList();
    }

    private String renderUser(List<PrfClaim> claims, List<KbKp> candidates) {
        StringBuilder claimsText = new StringBuilder();
        for (PrfClaim claim : claims) {
            claimsText.append("claimId ").append(claim.getId()).append("：")
                    .append(claim.getTitle() == null || claim.getTitle().isBlank() ? "" : "【" + claim.getTitle() + "】")
                    .append(claim.getWording()).append('\n');
        }
        StringBuilder candidateText = new StringBuilder();
        for (KbKp kp : candidates) {
            candidateText.append(kp.getId()).append(". ").append(kp.getTitle());
            if (kp.getSummary() != null && !kp.getSummary().isBlank()) {
                String summary = kp.getSummary().strip().replaceAll("\\s+", " ");
                candidateText.append(" — ").append(summary.length() > 60 ? summary.substring(0, 60) + "…" : summary);
            }
            candidateText.append('\n');
        }
        return PromptTemplate.render(readTemplate("prompts/claim_anchor_map.txt"),
                Map.of("kpCandidates", candidateText.toString(), "claims", claimsText.toString()));
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
