package com.zhiji.kb.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

@ConstantGroup("point_level")
public enum PointLevel implements ConstantItem {

    BASIC("basic", "基础"),
    MID("mid", "中级"),
    SENIOR("senior", "资深");

    private final String code;
    private final String name;

    PointLevel(String code, String name) {
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

    public static PointLevel fromCode(String code) {
        for (PointLevel value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知要点级别: " + code);
    }
}
