package com.zhiji.judge.domain;

/**
 * 档位（Grade，核心模块定义 6.1/6.5）：单知识点四档，由代码公式从命中分布计算，模型从不碰分。
 * 严格制公式（D3）：wrong 硬否决 → pass 全命中 → pass+good 全命中为 good → 再加 excellent≥1 为 excellent。
 */
public enum Grade {

    EXCELLENT("excellent", "优秀"),
    GOOD("good", "良好"),
    PASS("pass", "及格"),
    FAIL("fail", "不及格");

    private final String code;
    private final String label;

    Grade(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
