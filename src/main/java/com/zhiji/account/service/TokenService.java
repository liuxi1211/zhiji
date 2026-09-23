package com.zhiji.account.service;

import com.zhiji.account.domain.CustomerAccount;
import com.zhiji.account.domain.StaffAccount;
import com.zhiji.account.repo.CustomerAccountMapper;
import com.zhiji.account.repo.StaffAccountMapper;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.security.AuthProperties;
import com.zhiji.common.security.JwtSupport;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 会话签发与解析：JWT 每请求校验账号状态（停用即时失效，D18），不加会话表。
 * 员工与客户是两套会话，typ 互不认。
 */
@Service
public class TokenService {

    private final JwtSupport jwtSupport;
    private final StaffAccountMapper staffMapper;
    private final CustomerAccountMapper customerMapper;
    private final AuthProperties properties;

    public TokenService(JwtSupport jwtSupport, StaffAccountMapper staffMapper,
                        CustomerAccountMapper customerMapper, AuthProperties properties) {
        this.jwtSupport = jwtSupport;
        this.staffMapper = staffMapper;
        this.customerMapper = customerMapper;
        this.properties = properties;
    }

    public String issueStaff(StaffAccount account) {
        return jwtSupport.issue("staff", account.getId(), account.getRole(), ttlSeconds());
    }

    public String issueCustomer(CustomerAccount account) {
        return jwtSupport.issue("customer", account.getId(), "", ttlSeconds());
    }

    public StaffPrincipal resolveStaff(String token) {
        JwtSupport.Payload payload = jwtSupport.parse(token);
        if (!"staff".equals(payload.typ())) {
            throw BizException.unauthorizedToken();
        }
        StaffAccount account = staffMapper.selectById(payload.uid());
        if (account == null || !"active".equals(account.getStatus())) {
            throw BizException.unauthorizedToken();
        }
        return new StaffPrincipal(account.getId(), account.getRole());
    }

    public CustomerPrincipal resolveCustomer(String token) {
        JwtSupport.Payload payload = jwtSupport.parse(token);
        if (!"customer".equals(payload.typ())) {
            throw BizException.unauthorizedToken();
        }
        CustomerAccount account = customerMapper.selectById(payload.uid());
        if (account == null || !"active".equals(account.getStatus())) {
            throw BizException.unauthorizedToken();
        }
        return new CustomerPrincipal(account.getId());
    }

    private long ttlSeconds() {
        return Duration.ofDays(properties.getTokenTtlDays()).toSeconds();
    }

    public record StaffPrincipal(long id, String role) {
    }

    public record CustomerPrincipal(long id) {
    }
}
