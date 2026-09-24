package com.zhiji.pipeline.service;

import com.zhiji.llm.api.PromptTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Prompt 模板装载（核心模块定义 4.2：模板文本归业务模块 resources，llm 只提供渲染入口，
 * 改 prompt 是发版行为不是运行参数）。模板放 classpath:prompts/。
 */
@Component
public class PromptLoader {

    public String load(String name) {
        try (InputStream in = new ClassPathResource("prompts/" + name + ".md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Prompt 模板缺失: " + name, e);
        }
    }

    public String render(String name, Map<String, ?> vars) {
        return PromptTemplate.render(load(name), vars);
    }
}
