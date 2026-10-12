package com.zhiji.profile.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 技能条拍板状态（核心模块定义 4.1）：建档时逐条拍板——不熟的删掉、漏掉的加上、措辞不准的改掉。
 * 编辑措辞或锚点后自动回到待确认（重走拍板）。
 */
@ConstantGroup("claim_status")
public enum ClaimStatus implements ConstantItem {

    UNCONFIRMED("unconfirmed", "待确认"),
    CONFIRMED("confirmed", "已确认");

    private final String code;
    private final String name;

    ClaimStatus(String code, String name) {
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
