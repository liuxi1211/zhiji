package com.zhiji.pipeline.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/** 素材文档状态机（核心模块定义附录 A 8.2 / B端管理台PRD 6.2）。 */
@ConstantGroup("source_doc_status")
public enum SourceDocStatus implements ConstantItem {

    PENDING("pending", "待解析"),
    PARSED("parsed", "解析成功·待拆题"),
    PARSE_FAILED("parse_failed", "解析失败"),
    SPLIT("split", "已拆题入池");

    private final String code;
    private final String name;

    SourceDocStatus(String code, String name) {
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

    public static SourceDocStatus fromCode(String code) {
        for (SourceDocStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知素材状态: " + code);
    }
}
