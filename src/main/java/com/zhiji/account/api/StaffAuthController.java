package com.zhiji.account.api;

import com.zhiji.account.service.StaffAuthService;
import com.zhiji.common.security.AuthContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台登录（员工专用入口，无验证码——入口不做公开引流，初始口令线下交付）。
 */
@RestController
@RequestMapping("/api/auth/staff")
@Validated
public class StaffAuthController {

    private final StaffAuthService staffAuthService;

    public StaffAuthController(StaffAuthService staffAuthService) {
        this.staffAuthService = staffAuthService;
    }

    public record LoginReq(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "口令不能为空") String password) {
    }

    public record MeResp(long id, String username, String role) {
    }

    @PostMapping("/login")
    public StaffAuthService.LoginResult login(@Validated @RequestBody LoginReq req) {
        return staffAuthService.login(req.username(), req.password());
    }

    @GetMapping("/me")
    public MeResp me() {
        Long staffId = AuthContext.staffId();
        var me = staffAuthService.me(staffId);
        return new MeResp(me.id(), me.username(), me.role());
    }
}
