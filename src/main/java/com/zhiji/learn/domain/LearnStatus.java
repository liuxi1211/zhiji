package com.zhiji.learn.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 知识点学习状态值（核心模块定义 4.2/6.5，learn_state.status）：
 * learning（进行中）/ quick（速览通过，抽查范围）/ pass（判定通过）/ weak（待加强，弱点加权）/ abandoned（已放弃）。
 */
@ConstantGroup("learn_status")
public enum LearnStatus implements ConstantItem {

    LEARNING("learning", "进行中"),
    QUICK("quick", "速览通过"),
    PASS("pass", "判定通过"),
    WEAK("weak", "待加强"),
    ABANDONED("abandoned", "已放弃");

    private final String code;
    private final String name;

    LearnStatus(String code, String name) {
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

    /** 是否算「已推进」：quick/pass/weak 三态（单元完成度与复习池入池口径，R5）。 */
    public static boolean isProgressed(String code) {
        return QUICK.code.equals(code) || PASS.code.equals(code) || WEAK.code.equals(code);
    }
}
