package com.zhiji.kb.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

@ConstantGroup("freq_source")
public enum FreqSource implements ConstantItem {

    MANUAL("manual", "人工标定"),
    INTERVIEW("interview", "面经信号"),
    COVERAGE("coverage", "题库覆盖");

    private final String code;
    private final String name;

    FreqSource(String code, String name) {
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

    public static FreqSource fromCode(String code) {
        for (FreqSource value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知频率信号来源: " + code);
    }
}
