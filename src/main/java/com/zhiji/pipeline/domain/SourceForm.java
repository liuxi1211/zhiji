package com.zhiji.pipeline.domain;

import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.common.constant.ConstantItem;

/**
 * 素材内容形态（知识库PRD 九-2 / 决策清单 D22）：题库型做题-答配对、资料型章节切分 + AI 反推出题、
 * 面经型抽题供信号。三路拆分汇成统一候选条目。
 */
@ConstantGroup("source_form")
public enum SourceForm implements ConstantItem {

    QUESTION_BANK("question_bank", "题库型"),
    READING("reading", "资料型"),
    INTERVIEW("interview", "面经型");

    private final String code;
    private final String name;

    SourceForm(String code, String name) {
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

    public static SourceForm fromCode(String code) {
        for (SourceForm value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知内容形态: " + code);
    }
}
