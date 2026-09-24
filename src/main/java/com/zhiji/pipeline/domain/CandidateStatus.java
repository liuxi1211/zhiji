package com.zhiji.pipeline.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/** 候选条目状态机（核心模块定义附录 A 8.2 / B端管理台PRD 7.1）。 */
@ConstantGroup("candidate_status")
public enum CandidateStatus implements ConstantItem {

    PENDING("pending", "待研判"),
    MATCHED("matched", "已研判·待成稿"),
    CONVERTING("converting", "成稿中"),
    CONVERTED("converted", "已转化入正式库"),
    MERGED("merged", "已并入为目标知识点变体"),
    DISCARDED("discarded", "已放弃");

    private final String code;
    private final String name;

    CandidateStatus(String code, String name) {
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

    /** 已进入工作流的终态与在途态：删除素材文档前必须清空这些条目。 */
    public boolean inFlightOrTerminal() {
        return this != PENDING;
    }

    public static CandidateStatus fromCode(String code) {
        for (CandidateStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知候选状态: " + code);
    }
}
