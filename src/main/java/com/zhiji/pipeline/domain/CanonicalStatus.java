package com.zhiji.pipeline.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/** 归一题状态机（核心模块定义附录 A 8.2 / 决策清单 D21）。 */
@ConstantGroup("canonical_status")
public enum CanonicalStatus implements ConstantItem {

    PENDING("pending", "AI 聚合待确认"),
    CONFIRMED("confirmed", "人工确认入图"),
    BUILT("built", "已建设成知识点");

    private final String code;
    private final String name;

    CanonicalStatus(String code, String name) {
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

    public static CanonicalStatus fromCode(String code) {
        for (CanonicalStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知归一题状态: " + code);
    }
}
