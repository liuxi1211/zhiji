package com.zhiji.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.account.domain.StaffAccount;
import com.zhiji.account.repo.StaffAccountMapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthContext;
import com.zhiji.common.security.PasswordHasher;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AccountAdminService {

    private static final String SELF_DISABLE_MESSAGE = "不能停用当前登录的账号";

    private final StaffAccountMapper staffMapper;
    private final PasswordHasher passwordHasher;

    public AccountAdminService(StaffAccountMapper staffMapper, PasswordHasher passwordHasher) {
        this.staffMapper = staffMapper;
        this.passwordHasher = passwordHasher;
    }

    public record StaffAccountView(long id, String username, String role, String status,
                                   String createdAt, String lastLoginAt) {
    }

    public List<StaffAccountView> list() {
        return staffMapper.selectList(new LambdaQueryWrapper<StaffAccount>()
                        .orderByDesc(StaffAccount::getCreatedAt))
                .stream()
                .map(this::toView)
                .toList();
    }

    public StaffAccountView create(String username, String rawPassword, String role) {
        if (!"admin".equals(role) && !"editor".equals(role)) {
            throw BizException.validation("角色取值不合法");
        }
        if (exists(username)) {
            throw BizException.conflict("AUTH_USERNAME_TAKEN", "用户名已存在");
        }
        StaffAccount account = new StaffAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordHasher.hash(rawPassword));
        account.setRole(role);
        account.setStatus("active");
        staffMapper.insert(account);
        return toView(staffMapper.selectById(account.getId()));
    }

    public StaffAccountView updateStatus(long id, String status) {
        if (!"active".equals(status) && !"disabled".equals(status)) {
            throw BizException.validation("状态取值不合法");
        }
        if ("disabled".equals(status) && id == Long.valueOf(AuthContext.staffId())) {
            throw BizException.validation(SELF_DISABLE_MESSAGE);
        }
        StaffAccount account = requireAccount(id);
        account.setStatus(status);
        staffMapper.updateById(account);
        return toView(account);
    }

    public StaffAccountView resetPassword(long id, String rawPassword) {
        StaffAccount account = requireAccount(id);
        account.setPasswordHash(passwordHasher.hash(rawPassword));
        staffMapper.updateById(account);
        return toView(account);
    }

    private StaffAccount requireAccount(long id) {
        StaffAccount account = staffMapper.selectById(id);
        if (account == null) {
            throw BizException.notFound("后台人员不存在");
        }
        return account;
    }

    private boolean exists(String username) {
        return staffMapper.selectCount(new LambdaQueryWrapper<StaffAccount>()
                .eq(StaffAccount::getUsername, username)) > 0;
    }

    private StaffAccountView toView(StaffAccount account) {
        return new StaffAccountView(account.getId(), account.getUsername(), account.getRole(),
                account.getStatus(), account.getCreatedAt(), account.getLastLoginAt());
    }
}
