package com.zhiji.source.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.llm.api.PromptTemplate;
import com.zhiji.llm.service.PromptStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 素材库 AI 辅助（核心模块定义 5.1/6.4）：AI 改写（可带人工指令）、标题生成、追问链识别、
 * 概述生成、分类建议、反推出题。
 * AI 只以按钮形式存在——产物永远回到人手里：改写回编辑器、标题/追问链/概述分类反推均为建议，
 * 用不用由人、位置由人定、提交由人确认。
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
    private final PromptStore promptStore;
    private final JsonMapper jsonMapper = new JsonMapper();

    public SourceAiService(LlmClient llmClient, PromptStore promptStore) {
        this.llmClient = llmClient;
        this.promptStore = promptStore;
    }

    /** 标题生成产物（题面/文章题名，生成后可再改）。 */
    public record TitleResp(String title) {
    }

    /** 追问链识别草稿：前端补 id 与 status 后作为 followups 提交。 */
    public record FollowupDraft(int layer, String direction, String content) {
    }

    public record FollowupsResp(List<FollowupDraft> followups) {
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

    /** 改写模式：kp 面试题答案式 / doc 资料讲解式 / general 通用整理（素材行类型标记缺失时的兜底）。 */
    private static String normalizeMode(String mode) {
        return "kp".equals(mode) || "doc".equals(mode) ? mode : "general";
    }

    /** 流式改写：onDelta 逐片段回调（SSE 推给前端打字机式呈现），返回拼接后的完整正文。 */
    public String rewriteStream(String content, String mode, String instruction,
            java.util.function.Consumer<String> onDelta) {
        String text = requireContent(content);
        return llmClient.streamComplete(LlmRequest.admin(LlmPurpose.SOURCE_REWRITE,
                prompt("source_rewrite",
                        Map.of("content", text, "mode", normalizeMode(mode), "instruction", normalizeInstruction(instruction))))
                .timeoutSeconds(REWRITE_TIMEOUT_SECONDS)
                .maxTokens(REWRITE_MAX_TOKENS)
                .build(), onDelta);
    }

    /** 人工改写指令规整：空指令提示模板忽略该段。 */
    private static String normalizeInstruction(String instruction) {
        return instruction == null || instruction.isBlank() ? "（无特别要求，按通用规则整理）" : instruction.strip();
    }

    /** 标题生成：内容明确后按定类拟题（kp 题面问句 / doc 文章题名），生成后可再改。 */
    public TitleResp title(String content, String mode) {
        String text = requireContent(content);
        String raw = llmClient.complete(LlmRequest.admin(LlmPurpose.SOURCE_TITLE,
                prompt("source_title", Map.of("content", text, "mode", normalizeMode(mode))))
                .timeoutSeconds(JSON_TASK_TIMEOUT_SECONDS)
                .build());
        String title = raw.strip().replaceFirst("^[\"'#]+", "").replaceFirst("[\"']+$", "");
        if (title.length() > 120) {
            title = title.substring(0, 120);
        }
        return new TitleResp(title);
    }

    /**
     * 追问链识别：问答题正文自带多问/下钻内容时，整理成追问层草稿（layer 从 1 递进）。
     * 只识别「内容里真实存在的下钻回答」，不凭空发明追问。
     */
    public FollowupsResp followups(String content) {
        String text = requireContent(content);
        FollowupsResp resp = llmClient.completeJson(LlmRequest.admin(LlmPurpose.SOURCE_FOLLOWUP,
                prompt("source_followup", Map.of("content", text)))
                .timeoutSeconds(JSON_TASK_TIMEOUT_SECONDS)
                .build(), FollowupsResp.class);
        List<FollowupDraft> drafts = resp.followups() == null ? List.of()
                : resp.followups().stream()
                        .filter(f -> f.content() != null && !f.content().isBlank())
                        .map(f -> {
                            int layer = Math.min(Math.max(f.layer(), 1), 20);
                            String direction = f.direction() == null ? "" : f.direction().strip();
                            return new FollowupDraft(layer, direction, f.content().strip());
                        })
                        .toList();
        return new FollowupsResp(drafts);
    }

    public String summarize(String content) {
        String text = requireContent(content);
        String raw = llmClient.complete(LlmRequest.admin(LlmPurpose.SOURCE_SUMMARY,
                prompt("source_summary", Map.of("content", text)))
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
                prompt("source_classify",
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
                prompt("source_infer", Map.of("content", text)))
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
                prompt("source_difficulty", Map.of("content", text.substring(0, Math.min(6000, text.length())))))
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
                    segmentAround(infer.anchor()), infer.question(), "inferred", null);
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

    private String prompt(String code, Map<String, ?> vars) {
        return PromptTemplate.render(promptStore.get(code), vars);
    }
}