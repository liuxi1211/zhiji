package com.zhiji.account.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 账号状态：停用即对应侧会话失效并拒绝登录（员工 / 客户两表同构）。
 */
@ConstantGroup("account_status")
public enum AccountStatus implements ConstantItem {

    ACTIVE("active", "正常"),
    DISABLED("disabled", "已停用");

    private final String code;
    private final String name;

    AccountStatus(String code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }
}
