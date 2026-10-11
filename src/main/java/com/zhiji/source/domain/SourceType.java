package com.zhiji.source.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 素材来源类型：手写 / 导入 / URL 抓取 / 反推出题。
 */
@ConstantGroup("src_type")
public enum SourceType implements ConstantItem {

    MANUAL("manual", "手写"),
    IMPORT("import", "导入"),
    URL("url", "URL 抓取"),
    INFERRED("inferred", "反推出题");

    private final String code;
    private final String name;

    SourceType(String code, String name) {
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
