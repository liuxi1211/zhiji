package com.zhiji.pipeline;

import com.zhiji.llm.service.LlmTransport;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 归一测试专用网络替身：chat 按脚本顺序返回（耗尽后回落 fallback）；embed 按文本精确向量，
 * 未预设的文本用文本哈希生成的 8 维伪随机单位向量——不同标题近似正交（余弦远低于阈值），
 * 相同标题向量相同（余弦 = 1），保证归一测试的确定性且不受用例执行顺序影响。
 */
public class ScriptedLlmTransport implements LlmTransport {

    private final Queue<String> scripted = new ConcurrentLinkedDeque<>();
    private volatile String fallbackResponse = "{}";
    private final Map<String, float[]> vectorByText = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger chatCalls = new AtomicInteger();

    /** 重置脚本并设单一响应（后续所有 chat 都返回它），计数清零。 */
    public void respond(String text) {
        scripted.clear();
        fallbackResponse = text;
        chatCalls.set(0);
    }

    /** 按顺序返回多次响应，耗尽后停留在最后一个。 */
    public void script(String... texts) {
        scripted.clear();
        fallbackResponse = texts[texts.length - 1];
        for (String text : texts) {
            scripted.add(text);
        }
        chatCalls.set(0);
    }

    /** 预设指定文本的向量（精确匹配标题，用于构造可控余弦）。 */
    public void setVector(String text, float[] vector) {
        vectorByText.put(text, vector);
    }

    /** 清空预设向量（用例间隔离：向量键按标题字符串，跨用例残留会污染kp映射判定）。 */
    public void clearVectors() {
        vectorByText.clear();
    }

    public int chatCalls() {
        return chatCalls.get();
    }

    @Override
    public String call(String provider, String model, Double temperature, Integer maxTokens,
            String systemPrompt, String userPrompt) {
        chatCalls.incrementAndGet();
        String response = scripted.poll();
        return response == null ? fallbackResponse : response;
    }

    @Override
    public List<float[]> embed(String provider, String model, List<String> texts) {
        return texts.stream().map(text -> {
            float[] preset = vectorByText.get(text);
            return preset != null ? preset : pseudoVector(text);
        }).toList();
    }

    /** 8 维伪随机单位向量：同文本相同，异文本近似正交。 */
    private static float[] pseudoVector(String text) {
        Random random = new Random(text.hashCode() * 31L + text.length());
        float[] vector = new float[8];
        double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat() - 0.5f;
            norm += vector[i] * vector[i];
        }
        if (norm == 0) {
            vector[0] = 1f;
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / Math.sqrt(norm));
        }
        return vector;
    }
}
