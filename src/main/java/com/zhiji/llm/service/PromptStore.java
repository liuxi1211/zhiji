package com.zhiji.llm.service;

import com.zhiji.common.config.ConfigRegistry;
import com.zhiji.llm.config.LlmPromptSection;
import com.zhiji.llm.domain.LlmPromptConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

/**
 * 提示词模板读取门面：运行时以库为准（llm.prompt 快照，LLM 管理页改后即生效）。
 * 库内缺该 code（理论不应发生，afterLoad 已并入新模板）时回读 classpath 文件兜底，
 * 保证新增模板在未重启种子前也能被读到。
 */
@Service
public class PromptStore {

    private final ConfigRegistry registry;

    public PromptStore(ConfigRegistry registry) {
        this.registry = registry;
    }

    public String get(String code) {
        LlmPromptConfig config = registry.get(LlmPromptSection.KEY);
        String text = config == null ? null : config.template(code);
        return text != null && !text.isBlank() ? text : readClasspath(code);
    }

    private String readClasspath(String code) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("prompts/" + code + ".txt")) {
            if (in == null) {
                throw new IllegalStateException("缺少提示词模板: " + code);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词模板失败: " + code, e);
        }
    }
}
