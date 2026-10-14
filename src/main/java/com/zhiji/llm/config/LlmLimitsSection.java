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
 * 配置域「限流与熔断」（llm.limits，2026-10 统一规划）：
 * C 端每账号日/周/月总量、B 端全站日/周/月总量、全站日熔断兜底——三窗口均硬限。
 * 前端渲染为数字表单（通用渲染器）。窗口口径：自然日 / 自然周（周一起） / 自然月（1 号起）。
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
        return "限流与熔断";
    }

    @Override
    public String description() {
        return "C 端按账号、B 端按全站，均为日/周/月硬限；全站熔断为 C+B 合计的日红线";
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public List<FieldDef> fields() {
        return List.of(
                FieldDef.of("customerDaily", "C 端 · 每账号每日", FieldType.NUMBER)
                        .min(0).help("学习端每个账号当天可触发的调用总量").build(),
                FieldDef.of("customerWeekly", "C 端 · 每账号每周", FieldType.NUMBER)
                        .min(0).help("自然周（周一起）").build(),
                FieldDef.of("customerMonthly", "C 端 · 每账号每月", FieldType.NUMBER)
                        .min(0).help("自然月（1 号起）").build(),
                FieldDef.of("staffDaily", "B 端 · 全站每日", FieldType.NUMBER)
                        .min(0).help("管理台全部入口共享的当日总额度").build(),
                FieldDef.of("staffWeekly", "B 端 · 全站每周", FieldType.NUMBER)
                        .min(0).help("自然周（周一起）").build(),
                FieldDef.of("staffMonthly", "B 端 · 全站每月", FieldType.NUMBER)
                        .min(0).help("自然月（1 号起）").build(),
                FieldDef.of("siteDailyTotal", "全站日熔断（C+B）", FieldType.NUMBER)
                        .min(0).help("兜底红线：全部调用当日合计达到即熔断，防异常刷量").build());
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
        if (value.customerDaily() < 0 || value.customerWeekly() < 0 || value.customerMonthly() < 0
                || value.staffDaily() < 0 || value.staffWeekly() < 0 || value.staffMonthly() < 0
                || value.siteDailyTotal() < 0) {
            throw BizException.validation("限额与熔断阈值不能为负");
        }
    }
}
