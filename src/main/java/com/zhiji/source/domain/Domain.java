package com.zhiji.source.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 领域清单（核心模块定义 5.3 种子草案，待拍板后可在此调整或迁配置）。
 * 进料时人工点选，AI 分类只在所选领域内进行——领域数量少、语义清晰是精度的前提。
 */
@ConstantGroup("domain")
public enum Domain implements ConstantItem {

    JAVA("java", "Java 语言与虚拟机"),
    DATABASE("database", "数据库"),
    MIDDLEWARE("middleware", "中间件与搜索"),
    DISTRIBUTED("distributed", "分布式与架构"),
    CS_BASIC("cs_basic", "计算机基础"),
    GO("go", "Go 语言"),
    ENGINEERING("engineering", "工程素养");

    private final String code;
    private final String name;

    Domain(String code, String name) {
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
