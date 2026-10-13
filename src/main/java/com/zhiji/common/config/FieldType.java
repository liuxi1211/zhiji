package com.zhiji.common.config;

/**
 * 配置字段控件类型（FieldDef.type）：决定前端通用渲染器把字段画成什么控件。
 * 只收录设置页真实用到的形态，新增控件类型前先确认通用渲染器能承载，防 schema 表达力失控。
 */
public enum FieldType {
    TEXT,
    TEXTAREA,
    NUMBER,
    SWITCH,
    SELECT,
    DATE,
    /** 只读徽标：值真则渲染 tagText 徽标，否则渲染占位符。 */
    TAG,
    /** 子表：值是集合，行来源见 FieldDef.rowKeySource（固定行=常量组 / 自由行=数组）。 */
    TABLE
}
