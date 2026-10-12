package com.zhiji.profile.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 档案状态机（核心模块定义 4.1）：草稿 → 确认 → 定稿。
 * 存在未确认技能条时不得定稿；未定稿的画像不参与学习路径生成（消费方 T11+）。
 * 推进/回退由技能条变更自动重算（ProfileService.recomputeStatus），定稿与撤销定稿是显式用户动作。
 */
@ConstantGroup("profile_status")
public enum ProfileStatus implements ConstantItem {

    DRAFT("draft", "草稿"),
    CONFIRMED("confirmed", "已确认"),
    FINALIZED("finalized", "已定稿");

    private final String code;
    private final String name;

    ProfileStatus(String code, String name) {
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
