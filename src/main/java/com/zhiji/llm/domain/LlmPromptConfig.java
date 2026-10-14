package com.zhiji.llm.domain;

import java.util.Map;

/**
 * 提示词模板配置（存 sys_config 的 llm.prompt）：模板 code → 文本。
 * 首次启动从 classpath prompts/*.txt 种子导入；运行时以库为准（PromptStore），
 * classpath 文件保留作「恢复默认」参照。code = 模板文件名去扩展名（judge、source_title…）。
 */
public record LlmPromptConfig(Map<String, String> templates) {

    public LlmPromptConfig {
        templates = templates == null ? Map.of() : templates;
    }

    public String template(String code) {
        return templates.get(code);
    }
}
