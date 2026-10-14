package com.zhiji.source.domain;

import java.util.List;

/**
 * 领域清单配置（存 sys_config 的 kb.domains）：领域 code + 显示名 + 覆盖范围。
 * 取代原 Domain 枚举成为运行时事实源（系统设置页可增删改，保存即生效）；
 * 经 ConstantRegistry.override 以常量组 domain 发布，各消费方（校验/展示/学习路径分域）无感跟随。
 *
 * coverage（覆盖范围，可空）：覆盖主题清单 + 易混淆裁定，向导领域选择处以提示展示、
 * 并注入 AI 领域建议提示词——领域自动归类的准确性取决于此段文字的质量。
 */
public record DomainConfig(List<Item> items) {

    public DomainConfig {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 单个领域：code 落库（src_item/kb_topic/kb_kp/kb_doc.domain_code），name 供展示，coverage 供人读与 AI 判。 */
    public record Item(String code, String name, String coverage) {
    }
}
