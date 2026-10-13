package com.zhiji.common.config;

import java.util.List;

/**
 * 配置字段元数据（渲染契约）：GET /api/admin/configs 随域下发，前端通用渲染器据此生成表单/表格，
 * 后端代码是 schema 的唯一事实源——schema 变更属功能变更（发版），绝不入库。
 * 可选字段全部可空，前端按 null 缺省处理；用 Builder 构造避免 19 个位置参数。
 */
public record FieldDef(
        String key,
        String label,
        FieldType type,
        Double min,
        Double max,
        Double step,
        List<Option> options,
        /** 动态选项数据源：域 sources() 返回的 key（静态选项与动态数据源二选一）。 */
        String optionsSource,
        /** 选项依赖字段：optionsSource 为映射结构时，用该字段（域内或行内）的值作为取数 key。 */
        String optionsDependsOn,
        Boolean creatable,
        /** 三态开关：SELECT 出现空值选项（Boolean 三态 null=跟随默认）。 */
        Boolean triState,
        String nullLabel,
        String tagText,
        VisibleWhen visibleWhen,
        String help,
        Integer width,
        Integer minWidth,
        /** TABLE 专用：固定行来源 "constants:<常量组>"；null 表示自由行（值是数组，可增删）。 */
        String rowKeySource,
        /** TABLE 专用：列定义（递归 FieldDef）。 */
        List<FieldDef> columns) {

    /** 下拉选项：value 随 JSON 透传（字符串/布尔/数字），label 为显示名。 */
    public record Option(Object value, String label) {
    }

    /** 行内/域内条件可见性：op 取 eq / ne，field 引用同一行（或同域）内的兄弟字段。 */
    public record VisibleWhen(String field, String op, Object value) {
    }

    public static Builder of(String key, String label, FieldType type) {
        return new Builder(key, label, type);
    }

    public static final class Builder {

        private final String key;
        private final String label;
        private final FieldType type;
        private Double min;
        private Double max;
        private Double step;
        private List<Option> options;
        private String optionsSource;
        private String optionsDependsOn;
        private Boolean creatable;
        private Boolean triState;
        private String nullLabel;
        private String tagText;
        private VisibleWhen visibleWhen;
        private String help;
        private Integer width;
        private Integer minWidth;
        private String rowKeySource;
        private List<FieldDef> columns;

        private Builder(String key, String label, FieldType type) {
            this.key = key;
            this.label = label;
            this.type = type;
        }

        public Builder min(double value) {
            this.min = value;
            return this;
        }

        public Builder max(double value) {
            this.max = value;
            return this;
        }

        public Builder step(double value) {
            this.step = value;
            return this;
        }

        public Builder options(List<Option> value) {
            this.options = value;
            return this;
        }

        public Builder optionsSource(String value) {
            this.optionsSource = value;
            return this;
        }

        public Builder optionsDependsOn(String value) {
            this.optionsDependsOn = value;
            return this;
        }

        public Builder creatable() {
            this.creatable = true;
            return this;
        }

        public Builder triState(String nullLabel) {
            this.triState = true;
            this.nullLabel = nullLabel;
            return this;
        }

        public Builder tagText(String value) {
            this.tagText = value;
            return this;
        }

        public Builder visibleWhen(String field, String op, Object value) {
            this.visibleWhen = new VisibleWhen(field, op, value);
            return this;
        }

        public Builder help(String value) {
            this.help = value;
            return this;
        }

        public Builder width(int value) {
            this.width = value;
            return this;
        }

        public Builder minWidth(int value) {
            this.minWidth = value;
            return this;
        }

        public Builder rowKeySource(String value) {
            this.rowKeySource = value;
            return this;
        }

        public Builder columns(List<FieldDef> value) {
            this.columns = value;
            return this;
        }

        public FieldDef build() {
            return new FieldDef(key, label, type, min, max, step, options, optionsSource, optionsDependsOn,
                    creatable, triState, nullLabel, tagText, visibleWhen, help, width, minWidth,
                    rowKeySource, columns);
        }
    }
}
