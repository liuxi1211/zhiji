package com.zhiji.account.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 后台人员角色：固定两值不做可配置角色（决策清单 D18/D19）。
 */
@ConstantGroup("staff_role")
public enum StaffRole implements ConstantItem {

    ADMIN("admin", "管理员"),
    EDITOR("editor", "内容维护者");

    private final String code;
    private final String name;

    StaffRole(String code, String name) {
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
