package com.zhiji.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.account.domain.StaffAccount;
import com.zhiji.account.domain.StaffRole;
import com.zhiji.account.repo.StaffAccountMapper;
import com.zhiji.common.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首个管理员自举：sys_staff 为空时创建 admin/admin123——否则没有能登录后台创建账号的人。
 * 初始口令写 WARN 日志提示修改；修改口令能力随账号管理页提供。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final StaffAccountMapper staffMapper;
    private final PasswordHasher passwordHasher;

    public AdminBootstrap(StaffAccountMapper staffMapper, PasswordHasher passwordHasher) {
        this.staffMapper = staffMapper;
        this.passwordHasher = passwordHasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (staffMapper.selectCount(new LambdaQueryWrapper<StaffAccount>()
                .eq(StaffAccount::getRole, StaffRole.ADMIN.getCode())) > 0) {
            return;
        }
        StaffAccount admin = new StaffAccount();
        admin.setUsername("admin");
        admin.setPasswordHash(passwordHasher.hash("admin123"));
        admin.setRole(StaffRole.ADMIN.getCode());
        admin.setStatus("active");
        staffMapper.insert(admin);
        log.warn("已创建初始管理员账号 admin（初始口令 admin123），请登录后台后立即在账号管理页修改口令");
    }
}
