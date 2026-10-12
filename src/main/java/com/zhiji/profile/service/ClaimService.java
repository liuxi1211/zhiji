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
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 技能条服务（核心模块定义 4.1）：技能条 = 一段简历措辞 + 锚定知识点集合，建档时逐条拍板。
 *
 * 服务端红线：锚定的知识点必须真实存在于题库且已上线（available），对不上直接拒绝——
 * 不允许模型或前端编造考点（6.4 AI 边界）。措辞或锚点变更后技能条回到待确认，
 * 并触发档案状态重算（recomputeStatus，状态机单点在 ProfileService）。
 */
@Service
public class ClaimService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 措辞长度：一条简历主张的合理上限 */
    public static final int MAX_WORDING_CHARS = 200;

    /** 每档案技能条上限（D4 防滥用） */
    public static final int MAX_CLAIMS = 30;

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

    // —— 写入（档案归属由 Controller 经 ProfileService.getOwned 保证后传入） ——

    @Transactional
    public ClaimView add(PrfProfile profile, String wording, List<Long> anchorIds, String source) {
        long count = claimMapper.selectCount(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profile.getId()));
        if (count >= MAX_CLAIMS) {
            throw BizException.conflict("PRF_CLAIM_LIMIT", "每份档案最多 " + MAX_CLAIMS + " 条技能条");
        }
        PrfClaim claim = new PrfClaim();
        claim.setProfileId(profile.getId());
        claim.setWording(requireWording(wording));
        claim.setAnchorIds(jsonMapper.writeValueAsString(requireAnchors(anchorIds)));
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

    @Transactional
    public ClaimView update(PrfProfile profile, long claimId, String wording, List<Long> anchorIds) {
        PrfClaim claim = requireOwnedClaim(profile, claimId);
        claim.setWording(requireWording(wording));
        claim.setAnchorIds(jsonMapper.writeValueAsString(requireAnchors(anchorIds)));
        // 措辞或锚点变更即回待确认，重走拍板（4.1 逐条确认语义）
        claim.setStatus(ClaimStatus.UNCONFIRMED.getCode());
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

    // —— 锚点校验（红线：锚点必须真实存在且上线） ——

    /** 批量校验锚点为 available 知识点，返回去重后的 id 列表；空列表或含非法锚点直接拒绝。 */
    public List<Long> requireAnchors(List<Long> anchorIds) {
        if (anchorIds == null || anchorIds.isEmpty()) {
            throw BizException.validation("技能条至少锚定一个知识点");
        }
        List<Long> distinct = anchorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw BizException.validation("技能条至少锚定一个知识点");
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

    private PrfClaim requireOwnedClaim(PrfProfile profile, long claimId) {
        PrfClaim claim = claimMapper.selectById(claimId);
        if (claim == null || claim.getProfileId() == null || claim.getProfileId() != profile.getId().longValue()) {
            throw BizException.notFound("技能条不存在");
        }
        return claim;
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
        return new ClaimView(claim.getId(), claim.getWording(), anchorIds, anchors,
                claim.getSource(), claim.getStatus(), claim.getSortOrder() == null ? 0 : claim.getSortOrder());
    }
}
