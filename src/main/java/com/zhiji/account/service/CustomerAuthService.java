package com.zhiji.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.account.domain.CustomerAccount;
import com.zhiji.account.repo.CustomerAccountMapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.CaptchaService;
import com.zhiji.common.security.PasswordHasher;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

@Service
public class CustomerAuthService {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CustomerAccountMapper customerMapper;
    private final TokenService tokenService;
    private final PasswordHasher passwordHasher;
    private final CaptchaService captchaService;

    public CustomerAuthService(CustomerAccountMapper customerMapper, TokenService tokenService,
                               PasswordHasher passwordHasher, CaptchaService captchaService) {
        this.customerMapper = customerMapper;
        this.tokenService = tokenService;
        this.passwordHasher = passwordHasher;
        this.captchaService = captchaService;
    }

    public record AuthResult(String token, String username, long customerId) {
    }

    public record Me(long id, String username) {
    }

    public AuthResult register(String username, String rawPassword, String captchaId, String captchaCode) {
        if (!captchaService.verify(captchaId, captchaCode)) {
            throw BizException.of(400, "AUTH_CAPTCHA_INVALID", "验证码错误或已过期");
        }
        if (exists(username)) {
            throw BizException.conflict("AUTH_USERNAME_TAKEN", "用户名已存在");
        }
        CustomerAccount account = new CustomerAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordHasher.hash(rawPassword));
        account.setStatus("active");
        customerMapper.insert(account);
        return new AuthResult(tokenService.issueCustomer(account), account.getUsername(), account.getId());
    }

    public AuthResult login(String username, String rawPassword, String captchaId, String captchaCode) {
        if (!captchaService.verify(captchaId, captchaCode)) {
            throw BizException.of(400, "AUTH_CAPTCHA_INVALID", "验证码错误或已过期");
        }
        CustomerAccount account = customerMapper.selectOne(new LambdaQueryWrapper<CustomerAccount>()
                .eq(CustomerAccount::getUsername, username));
        if (account == null || !passwordHasher.verify(rawPassword, account.getPasswordHash())
                || !"active".equals(account.getStatus())) {
            throw BizException.of(401, "AUTH_LOGIN_FAILED", "用户名或口令不正确");
        }
        account.setLastLoginAt(LocalDateTime.now().format(LOG_TIME));
        customerMapper.updateById(account);
        return new AuthResult(tokenService.issueCustomer(account), account.getUsername(), account.getId());
    }

    public Me me(long customerId) {
        CustomerAccount account = customerMapper.selectById(customerId);
        if (account == null) {
            throw BizException.unauthorizedToken();
        }
        return new Me(account.getId(), account.getUsername());
    }

    private boolean exists(String username) {
        return customerMapper.selectCount(new LambdaQueryWrapper<CustomerAccount>()
                .eq(CustomerAccount::getUsername, username)) > 0;
    }
}
