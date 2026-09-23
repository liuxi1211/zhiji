package com.zhiji.llm.api;

import java.util.Map;

/**
 * 极简 Prompt 渲染入口：把模板里的 {{var}} 替换为入参值。
 * 模板文本归各业务模块 resources（核心模块定义 4.2：改 prompt 是发版行为，不是运行参数），
 * llm 只提供这个渲染入口，不引入模板引擎。
 */
public final class PromptTemplate {

    private PromptTemplate() {
    }

    public static String render(String template, Map<String, ?> vars) {
        String result = template;
        for (Map.Entry<String, ?> entry : vars.entrySet()) {
            String token = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            result = result.replace(token, value);
        }
        return result;
    }
}
