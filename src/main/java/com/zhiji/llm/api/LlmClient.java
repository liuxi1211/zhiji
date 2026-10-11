package com.zhiji.llm.api;

import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * 业务模块唯一依赖的 LLM 调用面（llm 包对外契约）。
 * 业务只认 purpose，不认 provider 与模型名；限额、熔断、JSON 校验重试、计数全在内部完成。
 *
 * 降级契约：限额/熔断超限抛 SYS_RATE_LIMITED / SYS_CIRCUIT_OPEN，
 * 模型返回两次不合法 JSON 或超时抛 SYS_PROVIDER_ERROR——由业务层决定降级动作（如判定解锁自评）。
 */
public interface LlmClient {

    /** 文本生成，返回模型原始文本。 */
    String complete(LlmRequest request);

    /** 流式文本生成：onDelta 逐片段回调（SSE 长文场景），最终返回拼接后的完整文本；成功/失败均只计一次。 */
    String streamComplete(LlmRequest request, Consumer<String> onDelta);

    /** 生成并解析为指定类型（强制 JSON），失败重试一次后仍不合法则抛 SYS_PROVIDER_ERROR。 */
    <T> T completeJson(LlmRequest request, Class<T> type);

    /** 批量向量化；返回与入参等长的向量列表。 */
    List<float[]> embedAll(LlmPurpose purpose, Long profileId, List<String> texts);
}
