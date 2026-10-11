package com.zhiji.source.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 素材状态机（核心模块定义 5.1）：待加工 → 已入库 / 已放弃。
 * 入库素材保留不删（B 端溯源资产，红线 5 原文隔离）。
 */
@ConstantGroup("src_status")
public enum SourceStatus implements ConstantItem {

    PENDING("pending", "待加工"),
    SUBMITTED("submitted", "已入库"),
    DISCARDED("discarded", "已放弃");

    private final String code;
    private final String name;

    SourceStatus(String code, String name) {
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
