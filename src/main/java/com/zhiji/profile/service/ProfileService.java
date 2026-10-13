package com.zhiji.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.profile.domain.PrfClaim;
import com.zhiji.profile.domain.PrfIntakeMsg;
import com.zhiji.profile.domain.PrfCase;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.domain.ProfileStatus;
import com.zhiji.profile.domain.ClaimStatus;
import com.zhiji.profile.domain.TargetLevel;
import com.zhiji.profile.repo.PrfClaimMapper;
import com.zhiji.profile.repo.PrfIntakeMsgMapper;
import com.zhiji.profile.repo.PrfCaseMapper;
import com.zhiji.profile.repo.PrfProfileMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 简历档案服务（核心模块定义 4.1）：档案 CRUD（每用户 ≤3 份）、当前档案切换、
 * 备考计划维护、画像状态机（draft→confirmed→finalized）与定稿门槛。
 *
 * 两条服务端红线：
 * - 归属校验：一切档案操作先过 getOwned，越权一律 404 不泄露存在性；
 * - 状态机单点：技能条变更后的状态推进/回退集中在 recomputeStatus（D7），
 *   存在未确认技能条时档案必须处于 draft，全部确认才可进入 confirmed，定稿是显式动作。
 */
@Service
public class ProfileService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每用户最多 3 份简历档案，档案之间进度完全隔离（4.1） */
    public static final int MAX_PROFILES = 3;

    public static final int MAX_RESUME_CHARS = 20_000;

    private final PrfProfileMapper profileMapper;
    private final PrfClaimMapper claimMapper;
    private final PrfCaseMapper caseMapper;
    private final PrfIntakeMsgMapper intakeMsgMapper;
    private final JdbcTemplate jdbc;

    public ProfileService(PrfProfileMapper profileMapper, PrfClaimMapper claimMapper,
            PrfCaseMapper caseMapper, PrfIntakeMsgMapper intakeMsgMapper, JdbcTemplate jdbc) {
        this.profileMapper = profileMapper;
        this.claimMapper = claimMapper;
        this.caseMapper = caseMapper;
        this.intakeMsgMapper = intakeMsgMapper;
        this.jdbc = jdbc;
    }

    // —— 视图契约（与前端 types.ts 一一对应） ——

    public record AnchorView(long id, String title) {
    }

    /** 技能条视图：anchors 为锚点标题实时反查，anchorStatus 承载映射状态（对话流：定稿后映射）。 */
    public record ClaimView(long id, String title, String wording, List<Long> anchorIds, List<AnchorView> anchors,
            String anchorStatus, String source, String status, int sortOrder) {
    }

    public record CaseView(long id, String projectName, String background, String metrics,
            String conflict, String result, String createdAt, String updatedAt) {
    }

    public record ProfileListItemView(long id, String name, String status, String targetLevel, boolean active,
            long claimCount, long unconfirmedCount, String createdAt, String updatedAt) {
    }

    public record ProfileView(long id, String name, String resumeText, Integer years, String position,
            String jdText, String techStack, String goodAt, String targetLevel, Integer planWeeks,
            Integer dailyMinutes, String status, boolean active, String finalizedAt, String createdAt,
            String updatedAt, List<ClaimView> claims, List<CaseView> cases) {
    }

    // —— 查询 ——

    public List<ProfileListItemView> list(long customerId) {
        List<PrfProfile> profiles = profileMapper.selectList(new LambdaQueryWrapper<PrfProfile>()
                .eq(PrfProfile::getCustomerId, customerId));
        Map<Long, List<PrfClaim>> claimsByProfile = claimsByProfile(profiles);
        return profiles.stream()
                .sorted(Comparator
                        .comparing((PrfProfile profile) -> profile.getIsActive() == null || profile.getIsActive() != 1)
                        .thenComparing(PrfProfile::getCreatedAt))
                .map(profile -> toListItem(profile, claimsByProfile.getOrDefault(profile.getId(), List.of())))
                .toList();
    }

    public PrfProfile getOwned(long profileId, long customerId) {
        PrfProfile profile = profileMapper.selectById(profileId);
        if (profile == null || profile.getCustomerId() == null || profile.getCustomerId() != customerId) {
            throw BizException.notFound("档案不存在");
        }
        return profile;
    }

    /** 当前档案（is_active=1）；无当前档案返回 null（消费方 T11 学习路径入口用，须自行处理空态）。 */
    public PrfProfile getActiveByCustomer(long customerId) {
        return profileMapper.selectList(new LambdaQueryWrapper<PrfProfile>()
                .eq(PrfProfile::getCustomerId, customerId)
                .eq(PrfProfile::getIsActive, 1)
                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /** 详情 = 档案 + 技能条 + 项目素材；技能条/素材视图由各自 service 提供（避免 service 互相依赖成环）。 */
    public ProfileView toDetailView(PrfProfile profile, List<ClaimView> claims, List<CaseView> cases) {
        return new ProfileView(profile.getId(), profile.getName(), profile.getResumeText(), profile.getYears(),
                profile.getPosition(), profile.getJdText(), profile.getTechStack(), profile.getGoodAt(),
                profile.getTargetLevel(), profile.getPlanWeeks(), profile.getDailyMinutes(), profile.getStatus(),
                profile.getIsActive() != null && profile.getIsActive() == 1,
                profile.getFinalizedAt(), profile.getCreatedAt(), profile.getUpdatedAt(), claims, cases);
    }

    // —— 创建（含注册默认档案） ——

    @Transactional
    public ProfileListItemView create(long customerId, String name, Integer years, String position, String resumeText) {
        if (name == null || name.isBlank()) {
            throw BizException.validation("档案名不能为空");
        }
        if (resumeText != null && resumeText.length() > MAX_RESUME_CHARS) {
            throw BizException.validation("简历全文长度 " + MAX_RESUME_CHARS + " 字以内");
        }
        long count = profileMapper.selectCount(new LambdaQueryWrapper<PrfProfile>()
                .eq(PrfProfile::getCustomerId, customerId));
        if (count >= MAX_PROFILES) {
            throw BizException.conflict("PRF_PROFILE_LIMIT", "每名用户最多维护 " + MAX_PROFILES + " 份简历档案");
        }
        PrfProfile profile = new PrfProfile();
        profile.setCustomerId(customerId);
        profile.setName(name.strip());
        profile.setResumeText(resumeText == null ? "" : resumeText);
        profile.setPosition(position == null ? "" : position.strip());
        profile.setJdText("");
        profile.setTechStack("");
        profile.setGoodAt("");
        profile.setStatus(ProfileStatus.DRAFT.getCode());
        // 首份档案自动成为当前档案（注册即建默认档案直接进建档，6.3）
        profile.setIsActive(count == 0 ? 1 : 0);
        String now = LocalDateTime.now().format(TS);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profileMapper.insert(profile);
        return toListItem(profile, List.of());
    }

    /** 注册成功即创建默认档案直接进建档（6.3）；已有档案的客户幂等跳过。 */
    @Transactional
    public void createDefaultProfile(long customerId) {
        long count = profileMapper.selectCount(new LambdaQueryWrapper<PrfProfile>()
                .eq(PrfProfile::getCustomerId, customerId));
        if (count > 0) {
            return;
        }
        create(customerId, "我的档案", null, "", null);
    }

    // —— 更新（备考计划整表替换语义：请求里带的字段全量生效，null 即清空） ——

    public record UpdateReq(String name, String resumeText, Integer years, String position, String jdText,
            String techStack, String goodAt, String targetLevel, Integer planWeeks, Integer dailyMinutes) {
    }

    @Transactional
    public ProfileListItemView update(long profileId, long customerId, UpdateReq req) {
        PrfProfile profile = getOwned(profileId, customerId);
        if (req.name() == null || req.name().isBlank()) {
            throw BizException.validation("档案名不能为空");
        }
        profile.setName(req.name().strip());
        profile.setResumeText(req.resumeText() == null ? "" : req.resumeText());
        if (req.resumeText() != null && req.resumeText().length() > MAX_RESUME_CHARS) {
            throw BizException.validation("简历全文长度 " + MAX_RESUME_CHARS + " 字以内");
        }
        if (req.years() != null && (req.years() < 0 || req.years() > 50)) {
            throw BizException.validation("工作年限取值 0~50");
        }
        profile.setYears(req.years());
        profile.setPosition(req.position() == null ? "" : req.position().strip());
        profile.setJdText(req.jdText() == null ? "" : req.jdText());
        profile.setTechStack(req.techStack() == null ? "" : req.techStack().strip());
        profile.setGoodAt(req.goodAt() == null ? "" : req.goodAt().strip());
        if (req.targetLevel() != null && !req.targetLevel().isBlank()) {
            TargetLevel level = TargetLevel.fromCode(req.targetLevel());
            if (level == null) {
                throw BizException.validation("目标职级取值不合法");
            }
            profile.setTargetLevel(level.getCode());
        } else {
            profile.setTargetLevel(null);
        }
        if (req.planWeeks() != null && (req.planWeeks() < 1 || req.planWeeks() > 104)) {
            throw BizException.validation("备考周期取值 1~104 周");
        }
        profile.setPlanWeeks(req.planWeeks());
        if (req.dailyMinutes() != null && (req.dailyMinutes() < 10 || req.dailyMinutes() > 720)) {
            throw BizException.validation("每日可投入时间取值 10~720 分钟");
        }
        profile.setDailyMinutes(req.dailyMinutes());
        profile.setUpdatedAt(LocalDateTime.now().format(TS));
        profileMapper.updateById(profile);
        return toListItem(profile, claimsByProfile(List.of(profile)).getOrDefault(profile.getId(), List.of()));
    }

    // —— 当前档案切换 ——

    @Transactional
    public void activate(long profileId, long customerId) {
        getOwned(profileId, customerId);
        // 先清后设，配合部分唯一索引 ux_prf_profile_active 保证每客户至多一个当前档案
        profileMapper.update(null, new LambdaUpdateWrapper<PrfProfile>()
                .eq(PrfProfile::getCustomerId, customerId)
                .eq(PrfProfile::getIsActive, 1)
                .set(PrfProfile::getIsActive, 0));
        PrfProfile profile = profileMapper.selectById(profileId);
        profile.setIsActive(1);
        profile.setUpdatedAt(LocalDateTime.now().format(TS));
        profileMapper.updateById(profile);
    }

    // —— 定稿 / 撤销定稿（显式用户动作） ——

    @Transactional
    public ProfileListItemView finalizeProfile(long profileId, long customerId) {
        PrfProfile profile = getOwned(profileId, customerId);
        if (profile.getTargetLevel() == null || profile.getTargetLevel().isBlank()) {
            throw BizException.of(400, "PRF_FINALIZE_NEED_LEVEL", "定稿前请先设定目标职级");
        }
        if (!ProfileStatus.CONFIRMED.getCode().equals(profile.getStatus())) {
            throw BizException.of(400, "PRF_FINALIZE_BLOCKED", "存在未确认的技能条，请先逐条拍板");
        }
        profile.setStatus(ProfileStatus.FINALIZED.getCode());
        profile.setFinalizedAt(LocalDateTime.now().format(TS));
        profile.setUpdatedAt(profile.getFinalizedAt());
        profileMapper.updateById(profile);
        return toListItem(profile, claimsByProfile(List.of(profile)).getOrDefault(profile.getId(), List.of()));
    }

    @Transactional
    public ProfileListItemView unfinalize(long profileId, long customerId) {
        PrfProfile profile = getOwned(profileId, customerId);
        if (!ProfileStatus.FINALIZED.getCode().equals(profile.getStatus())) {
            throw BizException.validation("档案尚未定稿");
        }
        profile.setStatus(ProfileStatus.CONFIRMED.getCode());
        profile.setFinalizedAt(null);
        profile.setUpdatedAt(LocalDateTime.now().format(TS));
        profileMapper.updateById(profile);
        return toListItem(profile, claimsByProfile(List.of(profile)).getOrDefault(profile.getId(), List.of()));
    }

    // —— 删除（PRD 4.1：二次确认在前端，服务端连带清理档案名下全部数据） ——

    /**
     * 删除档案及其名下全部数据：技能条、项目素材、建档对话、学习状态与练习会话（T11）。
     * 学习路径 / 判定 / 复习等消费方（T11+）落库后须在此同步追加级联清理。
     * 被删的是当前档案时，自动把最早创建的剩余档案设为当前，保持上下文连续。
     */
    @Transactional
    public void delete(long profileId, long customerId) {
        PrfProfile profile = getOwned(profileId, customerId);
        boolean wasActive = profile.getIsActive() != null && profile.getIsActive() == 1;
        claimMapper.delete(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profileId));
        caseMapper.delete(new LambdaQueryWrapper<PrfCase>()
                .eq(PrfCase::getProfileId, profileId));
        intakeMsgMapper.delete(new LambdaQueryWrapper<PrfIntakeMsg>()
                .eq(PrfIntakeMsg::getProfileId, profileId));
        // T11 级联（R14）：该档案的练习会话（customer 归属，created_by=档案 id）与作答、学习状态。
        // 经 JdbcTemplate 直连清理，避免 profile 与 learn/judge service 互相依赖成环（R14 说明）。
        // 外键顺序：judge_answer → learn_state → judge_session，最后再删档案行。
        List<Long> sessionIds = jdbc.queryForList(
                "SELECT id FROM judge_session WHERE owner_type = 'customer' AND created_by = ?",
                Long.class, profileId);
        if (!sessionIds.isEmpty()) {
            String in = sessionIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbc.update("DELETE FROM judge_answer WHERE session_id IN (" + in + ")");
            jdbc.update("DELETE FROM learn_state WHERE profile_id = ?", profileId);
            jdbc.update("DELETE FROM judge_session WHERE id IN (" + in + ")");
        } else {
            jdbc.update("DELETE FROM learn_state WHERE profile_id = ?", profileId);
        }
        profileMapper.deleteById(profileId);
        if (wasActive) {
            PrfProfile next = profileMapper.selectList(new LambdaQueryWrapper<PrfProfile>()
                    .eq(PrfProfile::getCustomerId, customerId)
                    .orderByAsc(PrfProfile::getCreatedAt)
                    .last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            if (next != null) {
                next.setIsActive(1);
                next.setUpdatedAt(LocalDateTime.now().format(TS));
                profileMapper.updateById(next);
            }
        }
    }

    // —— 状态机单点（D7）：技能条任何变更后调用 ——

    /**
     * 重算档案状态：有未确认技能条 → draft；全部确认 → confirmed。
     * finalized 态下发生技能条变更（编辑/新增/删除）自动降级回对应状态并清空定稿时间——
     * 定稿后仍可编辑主张，但重走确认与定稿。
     */
    @Transactional
    public void recomputeStatus(long profileId) {
        PrfProfile profile = profileMapper.selectById(profileId);
        if (profile == null) {
            return;
        }
        long unconfirmed = claimMapper.selectCount(new LambdaQueryWrapper<PrfClaim>()
                .eq(PrfClaim::getProfileId, profileId)
                .eq(PrfClaim::getStatus, ClaimStatus.UNCONFIRMED.getCode()));
        String target = unconfirmed > 0 ? ProfileStatus.DRAFT.getCode() : ProfileStatus.CONFIRMED.getCode();
        if (target.equals(profile.getStatus())) {
            return;
        }
        profile.setStatus(target);
        if (ProfileStatus.DRAFT.getCode().equals(target)) {
            profile.setFinalizedAt(null);
        }
        profile.setUpdatedAt(LocalDateTime.now().format(TS));
        profileMapper.updateById(profile);
    }

    // —— 内部 ——

    private Map<Long, List<PrfClaim>> claimsByProfile(List<PrfProfile> profiles) {
        if (profiles.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = profiles.stream().map(PrfProfile::getId).toList();
        return claimMapper.selectList(new LambdaQueryWrapper<PrfClaim>().in(PrfClaim::getProfileId, ids))
                .stream().collect(Collectors.groupingBy(PrfClaim::getProfileId));
    }

    private ProfileListItemView toListItem(PrfProfile profile, List<PrfClaim> claims) {
        long unconfirmed = claims.stream()
                .filter(claim -> ClaimStatus.UNCONFIRMED.getCode().equals(claim.getStatus())).count();
        return new ProfileListItemView(profile.getId(), profile.getName(), profile.getStatus(),
                profile.getTargetLevel(), profile.getIsActive() != null && profile.getIsActive() == 1,
                claims.size(), unconfirmed, profile.getCreatedAt(), profile.getUpdatedAt());
    }
}
