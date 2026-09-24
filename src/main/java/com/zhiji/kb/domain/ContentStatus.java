package com.zhiji.kb.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

@ConstantGroup("content_status")
public enum ContentStatus implements ConstantItem {

    DRAFT("draft", "草稿"),
    REVIEWED("reviewed", "已校对"),
    ACTIVE("active", "已上线"),
    RETIRED("retired", "已下线");

    private final String code;
    private final String name;

    ContentStatus(String code, String name) {
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

    public static ContentStatus fromCode(String code) {
        for (ContentStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知内容状态: " + code);
    }
}
