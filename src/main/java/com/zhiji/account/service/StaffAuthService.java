package com.zhiji.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.account.domain.StaffAccount;
import com.zhiji.account.repo.StaffAccountMapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.PasswordHasher;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

@Service
public class StaffAuthService {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StaffAccountMapper staffMapper;
    private final TokenService tokenService;
    private final PasswordHasher passwordHasher;

    public StaffAuthService(StaffAccountMapper staffMapper, TokenService tokenService, PasswordHasher passwordHasher) {
        this.staffMapper = staffMapper;
        this.tokenService = tokenService;
        this.passwordHasher = passwordHasher;
    }

    public record LoginResult(String token, String username, String role) {
    }

    public record Me(long id, String username, String role) {
    }

    public LoginResult login(String username, String rawPassword) {
        StaffAccount account = staffMapper.selectOne(new LambdaQueryWrapper<StaffAccount>()
                .eq(StaffAccount::getUsername, username));
        if (account == null || !passwordHasher.verify(rawPassword, account.getPasswordHash())
                || !"active".equals(account.getStatus())) {
            throw BizException.of(401, "AUTH_LOGIN_FAILED", "用户名或口令不正确");
        }
        account.setLastLoginAt(LocalDateTime.now().format(LOG_TIME));
        staffMapper.updateById(account);
        return new LoginResult(tokenService.issueStaff(account), account.getUsername(), account.getRole());
    }

    public Me me(long staffId) {
        StaffAccount account = staffMapper.selectById(staffId);
        if (account == null) {
            throw BizException.unauthorizedToken();
        }
        return new Me(account.getId(), account.getUsername(), account.getRole());
    }
}
