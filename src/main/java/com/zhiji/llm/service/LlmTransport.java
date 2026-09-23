package com.zhiji.llm.service;

import java.util.List;

/**
 * 网络出口缝：把「对接 Spring AI」隔离在一个可替换的实现后面。
 * 好处有二：路由/限额/JSON 重试/计数等编排逻辑可脱离网络单测；
 * 以及万一 Spring AI 版本不兼容时，只换本接口实现，llm.api 契约与业务代码零改动。
 */
public interface LlmTransport {

    String call(String provider, String model, Double temperature, Integer maxTokens,
            String systemPrompt, String userPrompt);

    List<float[]> embed(String provider, String model, List<String> texts);
}
