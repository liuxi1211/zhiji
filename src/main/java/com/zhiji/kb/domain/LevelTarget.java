package com.zhiji.kb.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

@ConstantGroup("level")
public enum LevelTarget implements ConstantItem {

    JUNIOR("junior", "初级"),
    MID("mid", "中级"),
    SENIOR("senior", "高级"),
    STAFF("staff", "资深"),
    EXPERT("expert", "专家");

    private final String code;
    private final String name;

    LevelTarget(String code, String name) {
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

    public static LevelTarget fromCode(String code) {
        for (LevelTarget value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知目标职级: " + code);
    }
}
