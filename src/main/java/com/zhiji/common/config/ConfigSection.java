package com.zhiji.common.config;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * 配置域声明：一个域 = 设置页一张可保存卡片 = sys_config 一行（config_value 为 JSON）= 一个强类型值对象。
 * 各业务模块实现本接口并注册为 Spring Bean，ConfigRegistry 自动收集——新增配置只写一个声明类，
 * 存取/快照/接口/前端渲染全部复用通用层。复杂校验留在 validate()（Java 代码），不 JSON 化。
 */
public interface ConfigSection<T> {

    /** 域标识（sys_config.config_key），规范 {module}.{name}，如 llm.route。 */
    String key();

    /** 卡片标题。 */
    String title();

    /** 卡片说明（同时作为 sys_config.description 落库）。 */
    String description();

    /** 页面卡片排序（小者在前）。 */
    int order();

    /** 渲染契约：字段元数据，前端据此生成表单/表格。 */
    List<FieldDef> fields();

    /** 空库种子默认值（registry 启动时落库）。 */
    T defaults();

    /** sys_config 的 JSON 文本 / PUT 请求体 → 强类型值。 */
    T parse(JsonNode json);

    /** 落库前域级校验（HTTP 保存路径专用；不合法抛 BizException）。 */
    void validate(T value);

    /**
     * 启动加载后的存量数据修复（只动缺的与坏的）：返回修复后的值，registry 与旧值不等则回写。
     * 典型用途：老库补缺失条目、模型下线归一化。默认原样返回。
     */
    default Object afterLoad(Object loaded) {
        return loaded;
    }

    /** 动态选项数据源（下拉 optionsSource 的取数池），随 GET /api/admin/configs 下发。 */
    default Map<String, Object> sources() {
        return Map.of();
    }
}
