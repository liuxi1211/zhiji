package com.zhiji.source.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 内容难度四档（核心模块定义 5.2 难度规则）：新增向导内 AI 建议、人工拍板；
 * 与职级要求正交（红线 6：难度只描述内容深浅，不做职级过滤）。
 */
@ConstantGroup("difficulty")
public enum Difficulty implements ConstantItem {

    JUNIOR("junior", "初级"),
    MID("mid", "中级"),
    SENIOR("senior", "高级"),
    EXPERT("expert", "资深");

    private final String code;
    private final String name;

    Difficulty(String code, String name) {
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
