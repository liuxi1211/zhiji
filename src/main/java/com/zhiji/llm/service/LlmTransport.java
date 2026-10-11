package com.zhiji.llm.service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 网络出口缝：把「对接 Spring AI」隔离在一个可替换的实现后面。
 * 好处有二：路由/限额/JSON 重试/计数等编排逻辑可脱离网络单测；
 * 以及万一 Spring AI 版本不兼容时，只换本接口实现，llm.api 契约与业务代码零改动。
 *
 * 只收 LlmCallSpec（Gateway 已合并路由配置与请求级覆盖），不含路由、限额、计数、JSON 解析。
 * stream：增量回调式流式生成（SSE 长文场景），onDelta 逐片段回调，返回拼接后的完整文本。
 */
public interface LlmTransport {

    String call(LlmCallSpec spec, String systemPrompt, String userPrompt);

    String stream(LlmCallSpec spec, String systemPrompt, String userPrompt, Consumer<String> onDelta);

    List<float[]> embed(String provider, String model, List<String> texts);
}
