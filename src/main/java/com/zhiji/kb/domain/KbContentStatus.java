package com.zhiji.kb.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 标准内容状态机（核心模块定义 5.2）：进门先过审核门，不校不上线。
 * draft(退回/恢复暂存) → pending(待审) → available(上线)；pending→deprecated(废弃)；
 * available→deprecated(弃用下线)；合并产物通过后源→merged(合并下线，可恢复)。
 */
@ConstantGroup("kb_status")
public enum KbContentStatus implements ConstantItem {

    DRAFT("draft", "草稿"),
    PENDING("pending", "待审核"),
    AVAILABLE("available", "已上线"),
    DEPRECATED("deprecated", "已弃用"),
    MERGED("merged", "合并下线");

    private final String code;
    private final String name;

    KbContentStatus(String code, String name) {
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
