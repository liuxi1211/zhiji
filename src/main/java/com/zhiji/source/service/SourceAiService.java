package com.zhiji.source.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.api.PromptTemplate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 素材库 AI 辅助（核心模块定义 5.1/6.4）：AI 改写、一键拆分预填、概述生成、分类建议、反推出题。
 * AI 只以按钮形式存在——产物永远回到人手里：改写回编辑器、拆分是可调预填、
 * 概述分类反推均为建议，用不用由人、位置由人定、提交由人确认。
 */
@Service
public class SourceAiService {

    private static final Logger log = LoggerFactory.getLogger(SourceAiService.class);

    private static final int MAX_CONTENT_CHARS = 12_000;

    /** 长任务超时（秒）：改写全文生成分钟级；拆分/反推/合并等 JSON 任务输出长，统一放宽到 120s。 */
    public static final int REWRITE_TIMEOUT_SECONDS = 180;
    public static final int JSON_TASK_TIMEOUT_SECONDS = 120;

    /**
     * 改写输出预算：路由 maxTokens 默认 4096，但思考模型（deepseek-v4.1 等）的推理 token 也计入输出预算，
     * 12k 字长文改写时思考耗尽预算 → 正文零输出直接 done。改写显式放宽到 16k，路由配置不再依赖。
     */
    private static final int REWRITE_MAX_TOKENS = 16_000;

    private final LlmClient llmClient;
    private final JsonMapper jsonMapper = new JsonMapper();

    public SourceAiService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** AI 改写产物（正文即产物；kp/doc 定类由向导入口预置 + 提交时人工选择，不再由 AI 建议）。 */
    public record RewriteResp(String content) {
    }

    /** 拆分预填项：anchor 为原文标题锚点，由前端据此切正文。 */
    public record SplitSlice(String title, String anchor) {
    }

    public record SplitResp(boolean needSplit, List<SplitSlice> slices) {
    }

    /** 分类建议：仅领域内推荐主题位置，采纳或无视由人。 */
    public record ClassifyResp(String topicName, String reason) {
    }

    /** 概述生成结果（Controller 包装返回）。 */
    public record SummaryResp(String summary) {
    }

    /** 反推面试问法：锚点 + 问法 + 难度。 */
    public record InferResp(String anchor, String question, String difficulty) {
    }

    /** 难度建议（6.4 用途清单：向导内建议四档难度，人工拍板）。 */
    public record DifficultyResp(String difficulty, String reason) {
    }

    public RewriteResp rewrite(String content) {
        String text = requireContent(content);
        String raw = llmClient.complete(LlmRequest.admin(LlmPurpose.SOURCE_REWRITE,
                prompt("prompts/source_rewrite.txt", Map.of("content", text)))
                .timeoutSeconds(REWRITE_TIMEOUT_SECONDS)
                .maxTokens(REWRITE_MAX_TOKENS)
                .build());
        return new RewriteResp(raw.strip());
    }

    /** 流式改写：onDelta 逐片段回调（SSE 推给前端打字机式呈现），返回拼接后的完整正文。 */
    public String rewriteStream(String content, java.util.function.Consumer<String> onDelta) {
        String text = requireContent(content);
        return llmClient.streamComplete(LlmRequest.admin(LlmPurpose.SOURCE_REWRITE,
                prompt("prompts/source_rewrite.txt", Map.of("content", text)))
                .timeoutSeconds(REWRITE_TIMEOUT_SECONDS)
                .maxTokens(REWRITE_MAX_TOKENS)
                .build(), onDelta);
    }

    public SplitResp split(String content) {
        String text = requireContent(content);
        SplitResp resp = llmClient.completeJson(LlmRequest.admin(LlmPurpose.SOURCE_SPLIT,
                prompt("prompts/source_split.txt", Map.of("content", text)))
                .timeoutSeconds(JSON_TASK_TIMEOUT_SECONDS)
                .build(), SplitResp.class);
        List<SplitSlice> slices = resp.slices() == null ? List.of()
                : resp.slices().stream()
                        .filter(s -> s.title() != null && !s.title().isBlank())
                        .toList();
        return new SplitResp(resp.needSplit(), slices);
    }

    public String summarize(String content) {
        String text = requireContent(content);
        String raw = llmClient.complete(LlmRequest.admin(LlmPurpose.SOURCE_SUMMARY,
                prompt("prompts/source_summary.txt", Map.of("content", text)))
                .timeoutSeconds(JSON_TASK_TIMEOUT_SECONDS)
                .build());
        String summary = raw.strip();
        if (summary.length() > 200) {
            summary = summary.substring(0, 200);
        }
        return summary;
    }

    public ClassifyResp classify(String content, String domain, List<String> topics) {
        String text = requireContent(content);
        String joined = String.join("、", topics);
        ClassifyResp resp = llmClient.completeJson(LlmRequest.admin(LlmPurpose.SOURCE_CLASSIFY,
                prompt("prompts/source_classify.txt",
                        Map.of("domain", domain, "topics", joined, "content", text.substring(0, Math.min(3000, text.length())))))
                .timeoutSeconds(JSON_TASK_TIMEOUT_SECONDS)
                .build(), ClassifyResp.class);
        String topic = resp.topicName() == null ? "" : resp.topicName().strip();
        // 只在候选主题内采纳：模型越界发明主题时降级为「采纳失败」，建议仅供采纳或无视
        if (!topic.isEmpty() && topics.stream().noneMatch(t -> t.equals(topic))) {
            log.info("分类建议越出候选清单（{}），不采纳", topic);
            return new ClassifyResp("", "建议不在候选主题内");
        }
        return new ClassifyResp(topic, resp.reason() == null ? "" : resp.reason().strip());
    }

    public List<InferResp> infer(String content) {
        String text = requireContent(content);
        InferResp[] items = llmClient.completeJson(LlmRequest.admin(LlmPurpose.SOURCE_INFER,
                prompt("prompts/source_infer.txt", Map.of("content", text)))
                .timeoutSeconds(JSON_TASK_TIMEOUT_SECONDS)
                .build(), InferResp[].class);
        List<InferResp> result = new ArrayList<>();
        for (InferResp item : items) {
            if (item.question() == null || item.question().isBlank()) {
                continue;
            }
            String difficulty = item.difficulty() == null ? "" : item.difficulty().strip();
            if (!List.of("初级", "中级", "高级", "资深").contains(difficulty)) {
                difficulty = "中级";
            }
            result.add(new InferResp(item.anchor() == null ? "" : item.anchor().strip(),
                    item.question().strip(), difficulty));
        }
        return result;
    }

    /** 难度建议：四档取值，模型越界时降级 mid（建议仅供采纳或无视）。 */
    public DifficultyResp difficulty(String content) {
        String text = requireContent(content);
        DifficultyResp resp = llmClient.completeJson(LlmRequest.admin(LlmPurpose.SOURCE_DIFFICULTY,
                prompt("prompts/source_difficulty.txt", Map.of("content", text.substring(0, Math.min(6000, text.length())))))
                .timeoutSeconds(JSON_TASK_TIMEOUT_SECONDS)
                .build(), DifficultyResp.class);
        String difficulty = resp.difficulty() == null ? "" : resp.difficulty().strip();
        if (!List.of("junior", "mid", "senior", "expert").contains(difficulty)) {
            log.info("难度建议越出四档清单（{}），降级为 mid", difficulty);
            difficulty = "mid";
        }
        return new DifficultyResp(difficulty, resp.reason() == null ? "" : resp.reason().strip());
    }

    /** 反推出题落库：每条问法生成一条面试题素材（source_type=inferred），锚点句引用做正文引子。 */
    public List<Long> saveInferred(List<InferResp> inferences, SourceService sourceService) {
        List<Long> ids = new ArrayList<>();
        for (InferResp infer : inferences) {
            SourceService.SourceItemView created = sourceService.create(infer.question(),
                    segmentAround(infer.anchor()), infer.question(), "inferred");
            ids.add(created.id());
        }
        return ids;
    }

    private String segmentAround(String anchor) {
        return anchor == null || anchor.isBlank() ? "" : "> " + anchor + "\n\n";
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw BizException.validation("素材正文为空，无法执行 AI 操作");
        }
        String text = content.strip();
        // 超长素材截断：AI 只做组织与起草，尾部截断不阻断人工流程（产物仍回人手里）
        return text.length() > MAX_CONTENT_CHARS ? text.substring(0, MAX_CONTENT_CHARS) + "\n\n（超长截断）" : text;
    }

    private String prompt(String path, Map<String, ?> vars) {
        String template = readTemplate(path);
        return PromptTemplate.render(template, vars);
    }

    private String readTemplate(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少 prompt 模板: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 模板失败: " + path, e);
        }
    }
}