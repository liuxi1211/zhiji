package com.zhiji.kb.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

@ConstantGroup("domain_status")
public enum DomainStatus implements ConstantItem {

    ACTIVE("active", "启用"),
    DISABLED("disabled", "停用");

    private final String code;
    private final String name;

    DomainStatus(String code, String name) {
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

    public static DomainStatus fromCode(String code) {
        for (DomainStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知技能域状态: " + code);
    }
}
