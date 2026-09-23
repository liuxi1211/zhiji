package com.zhiji.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiji.llm.api.PromptTemplate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Prompt 渲染入口：{{var}} 替换、缺值与 null 的边界（模板文本归业务模块 resources）。
 */
class PromptTemplateTest {

    @Test
    void replacesPlaceholders() {
        String result = PromptTemplate.render("题干：{{question}}\n答：{{answer}}",
                Map.of("question", "什么是 NRT", "answer", "近实时"));
        assertThat(result).isEqualTo("题干：什么是 NRT\n答：近实时");
    }

    @Test
    void nullValueBecomesEmptyString() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("quote", null);
        assertThat(PromptTemplate.render("[{{quote}}]", vars)).isEqualTo("[]");
    }

    @Test
    void unknownPlaceholderStaysUntouched() {
        assertThat(PromptTemplate.render("{{a}}-{{b}}", Map.of("a", "1"))).isEqualTo("1-{{b}}");
    }

    @Test
    void repeatedPlaceholderReplacedEverywhere() {
        assertThat(PromptTemplate.render("{{k}}/{{k}}", Map.of("k", "v"))).isEqualTo("v/v");
    }
}