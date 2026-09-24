package com.zhiji.kb.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

@ConstantGroup("freq_level")
public enum FreqLevel implements ConstantItem {

    HIGH("high", "高频"),
    MID("mid", "中频"),
    LOW("low", "低频");

    private final String code;
    private final String name;

    FreqLevel(String code, String name) {
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

    public static FreqLevel fromCode(String code) {
        for (FreqLevel value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知频率档位: " + code);
    }
}
