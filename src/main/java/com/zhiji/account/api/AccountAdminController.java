package com.zhiji.account.api;

import com.zhiji.account.service.AccountAdminService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台人员管理（仅 admin）。客户账号不在后台管理（D18）。
 */
@RestController
@RequestMapping("/api/admin/accounts")
@Validated
public class AccountAdminController {

    private final AccountAdminService accountAdminService;

    public AccountAdminController(AccountAdminService accountAdminService) {
        this.accountAdminService = accountAdminService;
    }

    public record CreateReq(
            @NotBlank(message = "用户名不能为空") @Size(min = 3, max = 32, message = "用户名长度 3~32") String username,
            @NotBlank(message = "初始口令不能为空") @Size(min = 6, max = 64, message = "初始口令长度 6~64") String password,
            @NotBlank(message = "角色不能为空") @Pattern(regexp = "admin|editor", message = "角色取值不合法") String role) {
    }

    public record StatusReq(
            @NotBlank(message = "状态不能为空") @Pattern(regexp = "active|disabled", message = "状态取值不合法") String status) {
    }

    public record ResetPasswordReq(
            @NotBlank(message = "新口令不能为空") @Size(min = 6, max = 64, message = "新口令长度 6~64") String password) {
    }

    @GetMapping
    public List<AccountAdminService.StaffAccountView> list() {
        return accountAdminService.list();
    }

    @PostMapping
    public AccountAdminService.StaffAccountView create(@Validated @RequestBody CreateReq req) {
        return accountAdminService.create(req.username(), req.password(), req.role());
    }

    @PostMapping("/{id}/status")
    public AccountAdminService.StaffAccountView updateStatus(@PathVariable long id,
                                                             @Validated @RequestBody StatusReq req) {
        return accountAdminService.updateStatus(id, req.status());
    }

    @PostMapping("/{id}/reset-password")
    public AccountAdminService.StaffAccountView resetPassword(@PathVariable long id,
                                                              @Validated @RequestBody ResetPasswordReq req) {
        return accountAdminService.resetPassword(id, req.password());
    }
}
