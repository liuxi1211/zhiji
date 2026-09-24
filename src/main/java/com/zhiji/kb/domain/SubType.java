package com.zhiji.kb.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

@ConstantGroup("sub_type")
public enum SubType implements ConstantItem {

    DEFAULT("default", "默认八股"),
    DESIGN_CHAIN("design_chain", "设计演进链"),
    NARRATIVE("narrative", "项目叙事");

    private final String code;
    private final String name;

    SubType(String code, String name) {
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

    public static SubType fromCode(String code) {
        for (SubType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知题型: " + code);
    }
}
