package com.zhiji.account.api;

import com.zhiji.account.service.CustomerAuthService;
import com.zhiji.common.security.AuthContext;
import com.zhiji.common.security.CaptchaService;
import com.zhiji.profile.service.ProfileService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户登录 / 注册（公开接口）。注册成功即登录（直接签发 token），
 * 并创建默认档案直接进建档（6.3，T10 落地）——默认档案创建在 api 层编排，
 * 避免 account.service 依赖业务包；创建失败时账号已存在，重登后可重试。
 */
@RestController
@RequestMapping("/api/auth/customer")
@Validated
public class CustomerAuthController {

    private final CustomerAuthService customerAuthService;
    private final CaptchaService captchaService;
    private final ProfileService profileService;

    public CustomerAuthController(CustomerAuthService customerAuthService, CaptchaService captchaService,
            ProfileService profileService) {
        this.customerAuthService = customerAuthService;
        this.captchaService = captchaService;
        this.profileService = profileService;
    }

    public record RegisterReq(
            @NotBlank(message = "用户名不能为空") @Size(min = 3, max = 32, message = "用户名长度 3~32") String username,
            @NotBlank(message = "口令不能为空") @Size(min = 6, max = 64, message = "口令长度 6~64") String password,
            @NotBlank String captchaId,
            @NotBlank String captchaCode) {
    }

    public record LoginReq(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "口令不能为空") String password,
            @NotBlank String captchaId,
            @NotBlank String captchaCode) {
    }

    public record MeResp(long id, String username) {
    }

    public record AuthResp(String token, String username, long customerId) {
    }

    @GetMapping("/captcha")
    public CaptchaService.Captcha captcha() {
        return captchaService.generate();
    }

    @PostMapping("/register")
    public AuthResp register(@Validated @RequestBody RegisterReq req) {
        var result = customerAuthService.register(req.username(), req.password(), req.captchaId(), req.captchaCode());
        profileService.createDefaultProfile(result.customerId());
        return new AuthResp(result.token(), result.username(), result.customerId());
    }

    @PostMapping("/login")
    public AuthResp login(@Validated @RequestBody LoginReq req) {
        var result = customerAuthService.login(req.username(), req.password(), req.captchaId(), req.captchaCode());
        return new AuthResp(result.token(), result.username(), result.customerId());
    }

    @GetMapping("/me")
    public MeResp me() {
        Long customerId = AuthContext.customerId();
        var me = customerAuthService.me(customerId);
        return new MeResp(me.id(), me.username());
    }
}
