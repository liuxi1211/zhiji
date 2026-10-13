package com.zhiji.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.KbContentStatus;
import com.zhiji.kb.domain.KbKp;
import com.zhiji.kb.repo.KbKpMapper;
import com.zhiji.profile.domain.ClaimStatus;
import com.zhiji.profile.domain.PrfClaim;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.repo.PrfClaimMapper;
import com.zhiji.profile.service.ProfileService.AnchorView;
import com.zhiji.profile.service.ProfileService.ClaimView;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 技能条服务（核心模块定义 4.1）：技能条 = 小标题 + 一段简历措辞 + 锚定知识点集合。
 *
 * 对话流（建档 PRD v2）下的语义变化：
 * - 技能条由对话「就用这版」整版采纳（acceptVersion），采纳即拍板（confirmed）；
 * - 锚点在定稿后由锚定映射补齐，采纳时允许零锚点（anchor_status=unmapped）——
 *   但用户/映射给出的任何锚点仍必须真实存在且上线（红线：AI 不编造考点）；
 * - 编辑措辞不再回退拍板状态（占位符填写不该撤销用户的确认）。
 */
@Service
public class ClaimService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 措辞长度：一条简历主张的合理上限（对话版正文 80~180 字 + 占位余量） */
    public static final int MAX_WORDING_CHARS = 300;

    /** 小标题长度上限 */
    public static final int MAX_TITLE_CHARS = 40;

    /** 每档案技能条上限（防滥用） */
    public static final int MAX_CLAIMS = 30;

    /** 未处理占位：【…】形式内联留白，定稿前必须清零（PRD v2 4.5 定稿门槛） */
    private static final Pattern PLACEHOLDER = Pattern.compile("【[^【】]+】");

    private final PrfClaimMapper claimMapper;
    private final KbKpMapper kpMapper;
    private final ProfileService profileService;
    private final JsonMapper jsonMapper = new JsonMapper();

    public ClaimService(PrfClaimMapper claimMapper, KbKpMapper kpMapper, ProfileService profileService) {
        this.claimMapper = claimMapper;
        this.kpMapper = kpMapper;
        this.profileService = profileService;
    }

    // —— 查询 ——

    public List<ClaimView> listByProfile(long profileId) {
        return claimMapper.selectList(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profileId)
                .orderByAsc(PrfClaim::getSortOrder)
                .orderByAsc(PrfClaim::getId))
                .stream().map(this::toView).toList();
    }

    // —— 对话采纳（就用这版 = 整版拍板） ——

    public record VersionClaim(String title, String content) {
    }

    /**
     * 采纳对话产出的完整版本：替换该档案全部 AI 来源技能条（手动添加的保留），
     * 采纳即拍板（confirmed），锚点留待定稿后映射（unmapped）。
     */
    @Transactional
    public List<ClaimView> acceptVersion(PrfProfile profile, List<VersionClaim> version) {
        List<VersionClaim> cleaned = version == null ? List.of()
                : version.stream()
                        .filter(item -> item != null && item.content() != null && !item.content().isBlank())
                        .toList();
        if (cleaned.isEmpty()) {
            throw BizException.validation("采纳的技能条版本为空");
        }
        if (cleaned.size() > MAX_CLAIMS) {
            throw BizException.conflict("PRF_CLAIM_LIMIT", "每份档案最多 " + MAX_CLAIMS + " 条技能条");
        }
        claimMapper.delete(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profile.getId())
                .eq(PrfClaim::getSource, "ai"));
        String now = LocalDateTime.now().format(TS);
        List<ClaimView> views = new ArrayList<>();
        int sort = 0;
        for (VersionClaim item : cleaned) {
            PrfClaim claim = new PrfClaim();
            claim.setProfileId(profile.getId());
            claim.setTitle(requireTitle(item.title(), sort + 1));
            claim.setWording(requireWording(item.content()));
            claim.setAnchorIds("[]");
            claim.setAnchorStatus("unmapped");
            claim.setSource("ai");
            claim.setStatus(ClaimStatus.CONFIRMED.getCode());
            claim.setSortOrder(sort++);
            claim.setCreatedAt(now);
            claim.setUpdatedAt(now);
            claimMapper.insert(claim);
            views.add(toView(claim));
        }
        profileService.recomputeStatus(profile.getId());
        return views;
    }

    // —— 单条维护（总览编辑 / 占位填写 / 锚点确认） ——

    @Transactional
    public ClaimView add(PrfProfile profile, String title, String wording, List<Long> anchorIds, String source) {
        long count = claimMapper.selectCount(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profile.getId()));
        if (count >= MAX_CLAIMS) {
            throw BizException.conflict("PRF_CLAIM_LIMIT", "每份档案最多 " + MAX_CLAIMS + " 条技能条");
        }
        PrfClaim claim = new PrfClaim();
        claim.setProfileId(profile.getId());
        claim.setTitle(requireTitle(title, (int) count + 1));
        claim.setWording(requireWording(wording));
        applyAnchors(claim, anchorIds);
        claim.setSource("ai".equals(source) ? "ai" : "manual");
        claim.setStatus(ClaimStatus.UNCONFIRMED.getCode());
        claim.setSortOrder((int) count);
        String now = LocalDateTime.now().format(TS);
        claim.setCreatedAt(now);
        claim.setUpdatedAt(now);
        claimMapper.insert(claim);
        profileService.recomputeStatus(profile.getId());
        return toView(claim);
    }

    /**
     * 编辑措辞 / 小标题 / 锚点。不再回退拍板状态（对话流：占位填写不该撤销用户确认）；
     * 锚点非空时校验并置 mapped，null 表示不改动锚点。
     */
    @Transactional
    public ClaimView update(PrfProfile profile, long claimId, String title, String wording, List<Long> anchorIds) {
        PrfClaim claim = requireOwnedClaim(profile, claimId);
        claim.setTitle(requireTitle(title, claim.getSortOrder() == null ? 1 : claim.getSortOrder() + 1));
        claim.setWording(requireWording(wording));
        if (anchorIds != null) {
            applyAnchors(claim, anchorIds);
        }
        claim.setUpdatedAt(LocalDateTime.now().format(TS));
        claimMapper.updateById(claim);
        profileService.recomputeStatus(profile.getId());
        return toView(claim);
    }

    @Transactional
    public ClaimView confirm(PrfProfile profile, long claimId) {
        PrfClaim claim = requireOwnedClaim(profile, claimId);
        claim.setStatus(ClaimStatus.CONFIRMED.getCode());
        claim.setUpdatedAt(LocalDateTime.now().format(TS));
        claimMapper.updateById(claim);
        profileService.recomputeStatus(profile.getId());
        return toView(claim);
    }

    @Transactional
    public void delete(PrfProfile profile, long claimId) {
        PrfClaim claim = requireOwnedClaim(profile, claimId);
        claimMapper.deleteById(claim.getId());
        profileService.recomputeStatus(profile.getId());
    }

    // —— 占位核对与导出（定稿门槛，PRD v2 4.5） ——

    /** 定稿门槛：存在未处理占位（【…】）即拒绝，报告条数。 */
    public void assertNoUnresolvedPlaceholders(long profileId) {
        long count = claimMapper.selectList(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profileId))
                .stream()
                .filter(claim -> claim.getWording() != null && PLACEHOLDER.matcher(claim.getWording()).find())
                .count();
        if (count > 0) {
            throw BizException.of(400, "PRF_PLACEHOLDER_UNRESOLVED",
                    "还有 " + count + " 条技能条存在未处理的【】占位，请先填写真值或删除占位");
        }
    }

    /** 渲染简历可粘贴的 Markdown（调用前先过占位核对）。 */
    public String renderMarkdown(List<ClaimView> claims) {
        StringBuilder builder = new StringBuilder("## 个人能力\n\n");
        for (ClaimView claim : claims) {
            if (claim.title() != null && !claim.title().isBlank()) {
                builder.append("**").append(claim.title()).append("**\n");
            }
            builder.append(claim.wording()).append("\n\n");
        }
        return builder.toString().strip();
    }

    // —— 锚点校验（红线：锚点必须真实存在且上线；对话流下允许零锚点，映射后补齐） ——

    /** 锚点非空时批量校验为 available 知识点，返回去重后的 id 列表；含非法锚点直接拒绝。 */
    public List<Long> validateAnchors(List<Long> anchorIds) {
        List<Long> distinct = anchorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw BizException.validation("锚定的知识点不能为空");
        }
        Map<Long, KbKp> found = kpMapper.selectBatchIds(distinct).stream()
                .filter(kp -> KbContentStatus.AVAILABLE.getCode().equals(kp.getStatus()))
                .collect(Collectors.toMap(KbKp::getId, kp -> kp, (a, b) -> a, LinkedHashMap::new));
        List<Long> missing = distinct.stream().filter(id -> !found.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw BizException.of(400, "PRF_ANCHOR_INVALID", "锚定的知识点不存在或未上线：id " + missing);
        }
        return distinct;
    }

    // —— 内部 ——

    private void applyAnchors(PrfClaim claim, List<Long> anchorIds) {
        if (anchorIds == null || anchorIds.isEmpty()) {
            claim.setAnchorIds("[]");
            claim.setAnchorStatus("unmapped");
            return;
        }
        claim.setAnchorIds(jsonMapper.writeValueAsString(validateAnchors(anchorIds)));
        claim.setAnchorStatus("mapped");
    }

    private PrfClaim requireOwnedClaim(PrfProfile profile, long claimId) {
        PrfClaim claim = claimMapper.selectById(claimId);
        if (claim == null || claim.getProfileId() == null || claim.getProfileId() != profile.getId().longValue()) {
            throw BizException.notFound("技能条不存在");
        }
        return claim;
    }

    private String requireTitle(String title, int fallbackIndex) {
        if (title == null || title.isBlank()) {
            return "能力方向 " + fallbackIndex;
        }
        String value = title.strip();
        if (value.length() > MAX_TITLE_CHARS) {
            throw BizException.validation("技能条小标题长度 " + MAX_TITLE_CHARS + " 字以内");
        }
        return value;
    }

    private String requireWording(String wording) {
        if (wording == null || wording.isBlank()) {
            throw BizException.validation("技能条措辞不能为空");
        }
        String text = wording.strip();
        if (text.length() > MAX_WORDING_CHARS) {
            throw BizException.validation("技能条措辞长度 " + MAX_WORDING_CHARS + " 字以内");
        }
        return text;
    }

    private ClaimView toView(PrfClaim claim) {
        List<Long> anchorIds = jsonMapper.readValue(
                claim.getAnchorIds() == null || claim.getAnchorIds().isBlank() ? "[]" : claim.getAnchorIds(),
                jsonMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        List<AnchorView> anchors = new ArrayList<>();
        if (!anchorIds.isEmpty()) {
            Map<Long, KbKp> found = kpMapper.selectBatchIds(anchorIds).stream()
                    .collect(Collectors.toMap(KbKp::getId, kp -> kp));
            anchorIds.forEach(id -> {
                KbKp kp = found.get(id);
                if (kp != null) {
                    anchors.add(new AnchorView(kp.getId(), kp.getTitle()));
                }
            });
        }
        return new ClaimView(claim.getId(), claim.getTitle(), claim.getWording(), anchorIds, anchors,
                claim.getAnchorStatus(), claim.getSource(), claim.getStatus(),
                claim.getSortOrder() == null ? 0 : claim.getSortOrder());
    }
}
