package com.zhiji.account.api;

import com.zhiji.account.service.CustomerAuthService;
import com.zhiji.common.security.AuthContext;
import com.zhiji.common.security.CaptchaService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户登录 / 注册（公开接口）。注册成功即登录（直接签发 token）；
 * 默认简历档案的创建随建档模块（T10）落地，注册后先进 /profile。
 */
@RestController
@RequestMapping("/api/auth/customer")
@Validated
public class CustomerAuthController {

    private final CustomerAuthService customerAuthService;
    private final CaptchaService captchaService;

    public CustomerAuthController(CustomerAuthService customerAuthService, CaptchaService captchaService) {
        this.customerAuthService = customerAuthService;
        this.captchaService = captchaService;
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

    @GetMapping("/captcha")
    public CaptchaService.Captcha captcha() {
        return captchaService.generate();
    }

    @PostMapping("/register")
    public Map<String, String> register(@Validated @RequestBody RegisterReq req) {
        var result = customerAuthService.register(req.username(), req.password(), req.captchaId(), req.captchaCode());
        return Map.of("token", result.token(), "username", result.username());
    }

    @PostMapping("/login")
    public Map<String, String> login(@Validated @RequestBody LoginReq req) {
        var result = customerAuthService.login(req.username(), req.password(), req.captchaId(), req.captchaCode());
        return Map.of("token", result.token(), "username", result.username());
    }

    @GetMapping("/me")
    public MeResp me() {
        Long customerId = AuthContext.customerId();
        var me = customerAuthService.me(customerId);
        return new MeResp(me.id(), me.username());
    }
}
