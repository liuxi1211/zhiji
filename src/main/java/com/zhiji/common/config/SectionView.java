package com.zhiji.common.config;

import java.util.List;
import java.util.Map;

/**
 * 配置域视图（GET /api/admin/configs 行元素 & PUT 保存回读）：schema（fields）+ 当前值（value）
 * + 动态选项池（sources）一次下发，前端据此自渲染，新增配置域前端零改动。
 */
public record SectionView(String key, String title, String description, int order,
        List<FieldDef> fields, Object value, Map<String, Object> sources) {
}
