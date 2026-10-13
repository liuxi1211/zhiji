package com.zhiji.profile.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 目标职级（Target Level，核心模块定义 6.5）：全产品的深度标尺，四档。
 * 取值与 kb_kp.level_rules、judge_session.level 的既有 CHECK 完全对齐（R8）。
 */
@ConstantGroup("target_level")
public enum TargetLevel implements ConstantItem {

    JUNIOR("junior", "初级"),
    MID("mid", "中级"),
    SENIOR("senior", "高级"),
    EXPERT("expert", "资深");

    private final String code;
    private final String name;

    TargetLevel(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /** 职级缺省推测结果（level=code，name=中文名） */
    public record Suggestion(String level, String name) {
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * 年限→职级缺省映射（4.1"没明确说时按工作年限推测缺省值"；映射标定按第八节后置，此处为暂定缺省）。
     * 0-2 年→初级、3-5 年→中级、6-8 年→高级、≥9 年→资深。
     */
    public static TargetLevel suggestByYears(Integer years) {
        if (years == null || years < 0) {
            return null;
        }
        if (years <= 2) {
            return JUNIOR;
        }
        if (years <= 5) {
            return MID;
        }
        if (years <= 8) {
            return SENIOR;
        }
        return EXPERT;
    }

    /** code 反查；未知值返回 null（调用方决定报错或忽略）。 */
    public static TargetLevel fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (TargetLevel level : values()) {
            if (level.code.equals(code)) {
                return level;
            }
        }
        return null;
    }
}
