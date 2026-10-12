package com.zhiji.profile.api;

import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.profile.domain.TargetLevel;
import com.zhiji.profile.service.CaseService;
import com.zhiji.profile.service.ClaimService;
import com.zhiji.profile.service.ProfileAiService;
import com.zhiji.profile.service.ProfileService;
import com.zhiji.profile.service.ProfileService.CaseView;
import com.zhiji.profile.service.ProfileService.ClaimView;
import com.zhiji.profile.service.ProfileService.ProfileListItemView;
import com.zhiji.profile.service.ProfileService.ProfileView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人档案接口（C 端客户会话，/api/profile）：建档（简历粘贴 + 补齐表单）、AI 起草技能条、
 * 逐条拍板、定稿，以及项目素材维护（核心模块定义 4.1）。
 * 归属校验全部经 ProfileService.getOwned（越权 404）；AI 起草产物不落库，采纳由客户端回写。
 */
@RestController
@RequestMapping("/api/profile")
@Validated
public class ProfileController {

    private final ProfileService profileService;
    private final ClaimService claimService;
    private final CaseService caseService;
    private final ProfileAiService profileAiService;

    public ProfileController(ProfileService profileService, ClaimService claimService,
            CaseService caseService, ProfileAiService profileAiService) {
        this.profileService = profileService;
        this.claimService = claimService;
        this.caseService = caseService;
        this.profileAiService = profileAiService;
    }

    public record CreateReq(
            @NotBlank(message = "档案名不能为空") @Size(max = 40, message = "档案名长度 40 以内") String name,
            @Min(value = 0, message = "工作年限取值 0~50") @Max(value = 50, message = "工作年限取值 0~50") Integer years,
            @Size(max = 120, message = "目标岗位长度 120 以内") String position) {
    }

    public record ClaimReq(
            @NotBlank(message = "技能条措辞不能为空") @Size(max = 200, message = "措辞长度 200 字以内") String wording,
            List<Long> anchorIds,
            String source) {
    }

    public record CaseReq(
            @NotBlank(message = "项目名不能为空") @Size(max = 80, message = "项目名长度 80 以内") String projectName,
            @Size(max = 2000, message = "各段长度 2000 字以内") String background,
            @Size(max = 2000, message = "各段长度 2000 字以内") String metrics,
            @Size(max = 2000, message = "各段长度 2000 字以内") String conflict,
            @Size(max = 2000, message = "各段长度 2000 字以内") String result) {
    }

    @GetMapping
    public List<ProfileListItemView> list() {
        return profileService.list(requireCustomer());
    }

    @PostMapping
    public ProfileListItemView create(@Validated @RequestBody CreateReq req) {
        return profileService.create(requireCustomer(), req.name(), req.years(), req.position());
    }

    /** 年限→目标职级缺省推测（4.1：没明确说时按工作年限推测缺省值，用户随时可改） */
    @GetMapping("/level-suggestion")
    public TargetLevel.Suggestion levelSuggestion(@RequestParam Integer years) {
        TargetLevel level = TargetLevel.suggestByYears(years);
        if (level == null) {
            throw BizException.validation("工作年限取值 0~50");
        }
        return new TargetLevel.Suggestion(level.getCode(), level.getName());
    }

    @GetMapping("/{id}")
    public ProfileView detail(@PathVariable long id) {
        long customerId = requireCustomer();
        var profile = profileService.getOwned(id, customerId);
        return profileService.toDetailView(profile,
                claimService.listByProfile(profile.getId()),
                caseService.listByProfile(profile.getId()));
    }

    @PutMapping("/{id}")
    public ProfileListItemView update(@PathVariable long id, @Validated @RequestBody ProfileService.UpdateReq req) {
        return profileService.update(id, requireCustomer(), req);
    }

    @PostMapping("/{id}/activate")
    public void activate(@PathVariable long id) {
        profileService.activate(id, requireCustomer());
    }

    @PostMapping("/{id}/finalize")
    public ProfileListItemView finalizeProfile(@PathVariable long id) {
        return profileService.finalizeProfile(id, requireCustomer());
    }

    @PostMapping("/{id}/unfinalize")
    public ProfileListItemView unfinalize(@PathVariable long id) {
        return profileService.unfinalize(id, requireCustomer());
    }

    // —— AI 起草（产物不落库；前端逐条采纳调 POST /{id}/claims） ——

    @PostMapping("/{id}/ai/claims")
    public List<ProfileAiService.ClaimDraftView> draftClaims(@PathVariable long id) {
        var profile = profileService.getOwned(id, requireCustomer());
        return profileAiService.draftClaims(profile);
    }

    // —— 技能条逐条拍板 ——

    @PostMapping("/{id}/claims")
    public ClaimView addClaim(@PathVariable long id, @Validated @RequestBody ClaimReq req) {
        var profile = profileService.getOwned(id, requireCustomer());
        return claimService.add(profile, req.wording(), req.anchorIds(), req.source());
    }

    @PutMapping("/{id}/claims/{claimId}")
    public ClaimView updateClaim(@PathVariable long id, @PathVariable long claimId,
            @Validated @RequestBody ClaimReq req) {
        var profile = profileService.getOwned(id, requireCustomer());
        return claimService.update(profile, claimId, req.wording(), req.anchorIds());
    }

    @PostMapping("/{id}/claims/{claimId}/confirm")
    public ClaimView confirmClaim(@PathVariable long id, @PathVariable long claimId) {
        var profile = profileService.getOwned(id, requireCustomer());
        return claimService.confirm(profile, claimId);
    }

    @DeleteMapping("/{id}/claims/{claimId}")
    public void deleteClaim(@PathVariable long id, @PathVariable long claimId) {
        var profile = profileService.getOwned(id, requireCustomer());
        claimService.delete(profile, claimId);
    }

    // —— 项目素材 ——

    @PostMapping("/{id}/cases")
    public CaseView addCase(@PathVariable long id, @Validated @RequestBody CaseReq req) {
        var profile = profileService.getOwned(id, requireCustomer());
        return caseService.add(profile, new CaseService.CaseReq(req.projectName(), req.background(),
                req.metrics(), req.conflict(), req.result()));
    }

    @PutMapping("/{id}/cases/{caseId}")
    public CaseView updateCase(@PathVariable long id, @PathVariable long caseId,
            @Validated @RequestBody CaseReq req) {
        var profile = profileService.getOwned(id, requireCustomer());
        return caseService.update(profile, caseId, new CaseService.CaseReq(req.projectName(), req.background(),
                req.metrics(), req.conflict(), req.result()));
    }

    @DeleteMapping("/{id}/cases/{caseId}")
    public void deleteCase(@PathVariable long id, @PathVariable long caseId) {
        var profile = profileService.getOwned(id, requireCustomer());
        caseService.delete(profile, caseId);
    }

    private long requireCustomer() {
        Long customerId = AuthContext.customerId();
        if (customerId == null) {
            throw BizException.unauthorizedToken();
        }
        return customerId;
    }
}
