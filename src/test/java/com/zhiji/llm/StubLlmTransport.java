package com.zhiji.llm;

import com.zhiji.llm.service.LlmTransport;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用网络替身：不触网，按脚本返回文本或抛异常。
 * 用 @Primary 注入，覆盖真实的 SpringAiTransport。
 */
public class StubLlmTransport implements LlmTransport {

    private volatile String nextText = "{}";
    private volatile RuntimeException nextError;
    private volatile String[] scripted;
    private final AtomicInteger callCount = new AtomicInteger();

    public void respond(String text) {
        this.nextText = text;
        this.nextError = null;
        this.scripted = null;
        this.callCount.set(0);
    }

    /** 按顺序返回多次响应（用于「首次非法 JSON、重试成功」场景）。 */
    public void script(String... texts) {
        this.scripted = texts;
        this.callCount.set(0);
        this.nextError = null;
    }

    public void fail(RuntimeException error) {
        this.nextError = error;
    }

    /** 清空所有脚本与计数，保证用例间互不影响。 */
    public void reset() {
        this.nextText = "{}";
        this.nextError = null;
        this.scripted = null;
        this.callCount.set(0);
    }

    public int callCount() {
        return callCount.get();
    }

    @Override
    public String call(String provider, String model, Double temperature, Integer maxTokens,
            String systemPrompt, String userPrompt) {
        int index = callCount.getAndIncrement();
        if (nextError != null) {
            throw nextError;
        }
        if (scripted != null) {
            return scripted[Math.min(index, scripted.length - 1)];
        }
        return nextText;
    }

    @Override
    public List<float[]> embed(String provider, String model, List<String> texts) {
        callCount.getAndIncrement();
        if (nextError != null) {
            throw nextError;
        }
        return texts.stream().map(t -> new float[] {1f, 0f}).toList();
    }
}
