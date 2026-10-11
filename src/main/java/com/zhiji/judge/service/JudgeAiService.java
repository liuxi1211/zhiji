package com.zhiji.judge.service;

import com.zhiji.judge.service.GradeCalculator.PointHit;
import com.zhiji.judge.service.GradeCalculator.PointSpec;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 判定对齐（核心模块定义 6.1 硬规则 1/2，D1/D2）：把单问自由作答对齐到评分要点。
 * 模型只报事实（wrong + 逐要点 hit/quote），答况结论由 JudgeService 代码推导；
 * 引用强制在代码侧执行——hit=true 但引不出作答原话的强制改 miss。
 * LLM 调用失败（两次非法 JSON / 超时）透传 SYS_PROVIDER_ERROR，由上层走降级自评。
 */
@Service
public class JudgeAiService {

    private static final Logger log = LoggerFactory.getLogger(JudgeAiService.class);

    /** 模型返回的原始事实（未经代码校验）。 */
    public record RawJudgment(Boolean wrong, String wrongNote, List<RawPoint> points) {
    }

    public record RawPoint(String id, Boolean hit, String quote) {
    }

    /** 代码校验后的对齐结果：命中的引用原话保留（进快照，6.1 配套机制）。 */
    public record AlignedJudgment(boolean wrong, String wrongNote, List<AlignedPoint> points) {
    }

    public record AlignedPoint(String id, String level, boolean hit, String quote) {

        public PointHit toHit() {
            return new PointHit(id, level, hit);
        }
    }

    private final LlmClient llmClient;
    private final JsonMapper jsonMapper = new JsonMapper();

    public JudgeAiService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public AlignedJudgment align(String question, String answer, List<PointSpec> specs) {
        String pointsJson = jsonMapper.writeValueAsString(specs);
        String prompt = PromptTemplate.render(readTemplate("prompts/judge.txt"), Map.of(
                "question", question,
                "answer", answer,
                "points", pointsJson));
        // completeJson 自带「非法 JSON 重试一次 → SYS_PROVIDER_ERROR」，正好承接 6.1 重试降级路径
        RawJudgment raw = llmClient.completeJson(LlmRequest.user(LlmPurpose.JUDGE_PRACTICE, null, prompt).build(),
                RawJudgment.class);
        return sanitize(raw, answer, specs);
    }

    /** 引用强制 + 未知要点过滤（D2）：结论只信代码校验过的部分。 */
    private AlignedJudgment sanitize(RawJudgment raw, String answer, List<PointSpec> specs) {
        Set<String> knownIds = specs.stream().map(PointSpec::id).collect(Collectors.toSet());
        Map<String, String> levelById = specs.stream()
                .collect(Collectors.toMap(PointSpec::id, PointSpec::level));
        List<AlignedPoint> points = new ArrayList<>();
        int rejectedQuotes = 0;
        for (RawPoint point : raw.points() == null ? List.<RawPoint>of() : raw.points()) {
            if (point.id() == null || !knownIds.contains(point.id())) {
                continue;
            }
            boolean claimed = Boolean.TRUE.equals(point.hit());
            boolean hit = claimed && quoteBelongs(point.quote(), answer);
            if (claimed && !hit) {
                rejectedQuotes++;
            }
            points.add(new AlignedPoint(point.id(), levelById.getOrDefault(point.id(), ""),
                    hit, hit ? point.quote() : ""));
        }
        if (rejectedQuotes > 0) {
            log.info("引用强制：{} 个命中因引不出作答原话改判未命中", rejectedQuotes);
        }
        boolean wrong = Boolean.TRUE.equals(raw.wrong());
        return new AlignedJudgment(wrong, raw.wrongNote() == null ? "" : raw.wrongNote().strip(), points);
    }

    /** 原话校验：quote 与 answer 去空白归一后必须是子串（6.1 硬规则 2：引不出原话算未命中）。 */
    private boolean quoteBelongs(String quote, String answer) {
        if (quote == null || quote.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        String normQuote = quote.strip().replaceAll("\\s+", "");
        String normAnswer = answer.replaceAll("\\s+", "");
        return !normQuote.isEmpty() && normAnswer.contains(normQuote);
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
