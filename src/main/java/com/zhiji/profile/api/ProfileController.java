package com.zhiji.profile.api;

import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.profile.domain.TargetLevel;
import com.zhiji.profile.service.AnchorMapService;
import com.zhiji.profile.service.CaseService;
import com.zhiji.profile.service.ClaimService;
import com.zhiji.profile.service.IntakeChatService;
import com.zhiji.profile.service.IntakeChatService.ChatHistoryView;
import com.zhiji.profile.service.IntakeChatService.ChatTurnView;
import com.zhiji.profile.service.ProfileService;
import com.zhiji.profile.service.ProfileService.CaseView;
import com.zhiji.profile.service.ProfileService.ClaimView;
import com.zhiji.profile.service.ProfileService.ProfileListItemView;
import com.zhiji.profile.service.ProfileService.ProfileView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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
 * 个人档案接口（C 端客户会话，/api/profile）：对话头脑风暴式建档（PRD v2）——
 * 基础信息表单、对话起草与修订（首轮 + 多轮）、整版采纳、定稿核对（占位清零 + 备考计划）、
 * 简历 Markdown 导出、定稿后锚定映射与项目素材维护（核心模块定义 4.1）。
 * 归属校验全部经 ProfileService.getOwned（越权 404）；对话与映射走 PROFILE_DRAFT 通路。
 */
@RestController
@RequestMapping("/api/profile")
@Validated
public class ProfileController {

    private final ProfileService profileService;
    private final ClaimService claimService;
    private final CaseService caseService;
    private final IntakeChatService intakeChatService;
    private final AnchorMapService anchorMapService;

    public ProfileController(ProfileService profileService, ClaimService claimService,
            CaseService caseService, IntakeChatService intakeChatService, AnchorMapService anchorMapService) {
        this.profileService = profileService;
        this.claimService = claimService;
        this.caseService = caseService;
        this.intakeChatService = intakeChatService;
        this.anchorMapService = anchorMapService;
    }

    public record CreateReq(
            @NotBlank(message = "档案名不能为空") @Size(max = 40, message = "档案名长度 40 以内") String name,
            @Min(value = 0, message = "工作年限取值 0~50") @Max(value = 50, message = "工作年限取值 0~50") Integer years,
            @Size(max = 120, message = "目标岗位长度 120 以内") String position,
            @Size(max = 20000, message = "简历全文长度 20000 字以内") String resumeText) {
    }

    public record ClaimReq(
            @Size(max = 40, message = "小标题长度 40 字以内") String title,
            @NotBlank(message = "技能条措辞不能为空") @Size(max = 300, message = "措辞长度 300 字以内") String wording,
            List<Long> anchorIds,
            String source) {
    }

    public record AcceptReq(@NotEmpty(message = "技能条版本不能为空") List<ClaimService.VersionClaim> claims) {
    }

    public record ChatTextReq(
            @NotBlank(message = "反馈内容不能为空") @Size(max = 2000, message = "反馈长度 2000 字以内") String text) {
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
        return profileService.create(requireCustomer(), req.name(), req.years(), req.position(), req.resumeText());
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

    /** 删除档案：连带清理技能条、项目素材、建档对话（学习记录等消费方落库后同步级联） */
    @DeleteMapping("/{id}")
    public void deleteProfile(@PathVariable long id) {
        profileService.delete(id, requireCustomer());
    }

    // —— 对话头脑风暴（PRD v2 4.4：首轮即出完整草案，之后自然语言修订） ——

    @GetMapping("/{id}/chat")
    public ChatHistoryView chatHistory(@PathVariable long id) {
        return intakeChatService.history(profileService.getOwned(id, requireCustomer()));
    }

    @PostMapping("/{id}/chat/first")
    public ChatTurnView chatFirst(@PathVariable long id) {
        return intakeChatService.firstRound(profileService.getOwned(id, requireCustomer()));
    }

    @PostMapping("/{id}/chat/revise")
    public ChatTurnView chatRevise(@PathVariable long id, @Validated @RequestBody ChatTextReq req) {
        return intakeChatService.revise(profileService.getOwned(id, requireCustomer()), req.text());
    }

    @DeleteMapping("/{id}/chat")
    public void chatClear(@PathVariable long id) {
        intakeChatService.clear(profileService.getOwned(id, requireCustomer()));
    }

    // —— 技能条（就用这版 = 整版采纳；总览单条编辑 / 锚点确认） ——

    @PostMapping("/{id}/claims/accept")
    public List<ClaimView> acceptVersion(@PathVariable long id, @Validated @RequestBody AcceptReq req) {
        return claimService.acceptVersion(profileService.getOwned(id, requireCustomer()), req.claims());
    }

    @PostMapping("/{id}/claims")
    public ClaimView addClaim(@PathVariable long id, @Validated @RequestBody ClaimReq req) {
        var profile = profileService.getOwned(id, requireCustomer());
        return claimService.add(profile, req.title(), req.wording(), req.anchorIds(), req.source());
    }

    @PutMapping("/{id}/claims/{claimId}")
    public ClaimView updateClaim(@PathVariable long id, @PathVariable long claimId,
            @Validated @RequestBody ClaimReq req) {
        var profile = profileService.getOwned(id, requireCustomer());
        return claimService.update(profile, claimId, req.title(), req.wording(), req.anchorIds());
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

    // —— 锚定映射（定稿后执行，PRD v2 4.6） ——

    @PostMapping("/{id}/anchors/map")
    public AnchorMapService.MapResult mapAnchors(@PathVariable long id) {
        return anchorMapService.mapAnchors(profileService.getOwned(id, requireCustomer()));
    }

    // —— 定稿（占位核对是定稿门槛，PRD v2 4.5） ——

    @PostMapping("/{id}/finalize")
    public ProfileListItemView finalizeProfile(@PathVariable long id) {
        long customerId = requireCustomer();
        profileService.getOwned(id, customerId);
        // 占位清零是定稿门槛（PRD v2 4.5）：存在未处理【】占位先拒绝
        claimService.assertNoUnresolvedPlaceholders(id);
        return profileService.finalizeProfile(id, customerId);
    }

    @PostMapping("/{id}/unfinalize")
    public ProfileListItemView unfinalize(@PathVariable long id) {
        return profileService.unfinalize(id, requireCustomer());
    }

    // —— 简历技能段导出（PRD v2 4.5：渲染 Markdown，一键复制） ——

    @GetMapping("/{id}/export")
    public Map<String, String> exportMarkdown(@PathVariable long id) {
        long customerId = requireCustomer();
        var profile = profileService.getOwned(id, customerId);
        claimService.assertNoUnresolvedPlaceholders(profile.getId());
        return Map.of("markdown",
                claimService.renderMarkdown(claimService.listByProfile(profile.getId())));
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
