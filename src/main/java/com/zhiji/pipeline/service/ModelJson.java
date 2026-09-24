package com.zhiji.pipeline.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * pipeline 内的模型返回 JSON 解析：剥离 ```json 围栏后反序列化。
 * llm 网关已做围栏剥离，但业务侧拿到的是 complete 的原始文本，这里本地兜一层。
 */
final class ModelJson {

    private static final JsonMapper JSON = new JsonMapper();

    private ModelJson() {
    }

    static <T> T parse(String raw, TypeReference<T> type) {
        return JSON.readValue(stripCodeFence(raw), type);
    }

    static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int start = text.indexOf('\n');
        int end = text.lastIndexOf("```");
        if (start < 0 || end <= start) {
            return text;
        }
        return text.substring(start + 1, end).trim();
    }
}
