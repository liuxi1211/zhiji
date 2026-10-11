package com.zhiji.judge.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 答况（Verdict，核心模块定义 6.1/6.5）：判定的四值结论，「空」是独立值——没答就是没答，
 * 不用空内容代替。verdict 由代码推导（D1）：模型只报事实，结论在代码。
 */
@ConstantGroup("judge_verdict")
public enum Verdict implements ConstantItem {

    CORRECT("correct", "答对"),
    MISSING("missing", "答缺"),
    WRONG("wrong", "答错"),
    EMPTY("empty", "未答");

    private final String code;
    private final String name;

    Verdict(String code, String name) {
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
