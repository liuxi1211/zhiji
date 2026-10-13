package com.zhiji.llm.config;

import com.zhiji.common.config.ConfigSection;
import com.zhiji.common.config.FieldDef;
import com.zhiji.common.config.FieldType;
import com.zhiji.common.exception.BizException;
import com.zhiji.llm.domain.LlmLimits;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置域「限额与熔断」（llm.limits）：四个每日阈值，前端渲染为数字表单。
 * judgeDaily 为软上限（超限不阻断，仅告警）；其余为硬熔断（超限抛业务异常，由业务层降级）。
 */
@Component
public class LlmLimitsSection implements ConfigSection<LlmLimits> {

    public static final String KEY = "llm.limits";

    private final JsonMapper jsonMapper = new JsonMapper();

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "限额与熔断";
    }

    @Override
    public String description() {
        return "判定为软上限，超限仅告警；其余为硬熔断";
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public List<FieldDef> fields() {
        return List.of(
                FieldDef.of("judgeDaily", "判定类（练习 / 抽卡，次/档案/天）", FieldType.NUMBER)
                        .min(0).help("软上限，超限仅告警").build(),
                FieldDef.of("variantRewriteDaily", "变体现场改写（次/档案/天）", FieldType.NUMBER)
                        .min(0).build(),
                FieldDef.of("perProfileDailyTotal", "单档案每日合计（硬熔断）", FieldType.NUMBER)
                        .min(0).build(),
                FieldDef.of("siteDailyTotal", "全站每日总熔断", FieldType.NUMBER)
                        .min(0).build());
    }

    @Override
    public LlmLimits defaults() {
        return LlmLimits.defaults();
    }

    @Override
    public LlmLimits parse(JsonNode json) {
        return jsonMapper.treeToValue(json, LlmLimits.class);
    }

    @Override
    public void validate(LlmLimits value) {
        if (value.judgeDaily() < 0) {
            throw BizException.validation("判定限额不能为负");
        }
        if (value.variantRewriteDaily() < 0) {
            throw BizException.validation("变体改写限额不能为负");
        }
        if (value.perProfileDailyTotal() < 0) {
            throw BizException.validation("单档案合计限额不能为负");
        }
        if (value.siteDailyTotal() < 0) {
            throw BizException.validation("全站熔断阈值不能为负");
        }
    }
}
