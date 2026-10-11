package com.zhiji.kb.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.kb.service.KbContentService.AnswerSample;
import com.zhiji.kb.service.KbContentService.ContentItemView;
import com.zhiji.kb.service.KbContentService.DocView;
import com.zhiji.kb.service.KbContentService.Followup;
import com.zhiji.kb.service.KbContentService.KpView;
import com.zhiji.kb.service.KbContentService.LevelRule;
import com.zhiji.kb.service.KbContentService.ScoringPoint;
import com.zhiji.kb.service.KbContentService.UpdateKpReq;
import com.zhiji.kb.service.KbContentService.Variant;
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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同位合并服务（核心模块定义 5.2「同位去重与合并」）：同主题内容天然同位，重复一眼可见无需算法。
 * 可视化合并页最多勾选三份三栏并排对照；两种合并方式——AI 一键合并（产物回人手里）
 * 或人工参照手写（页面上方即可编辑）。产物作为新行进审核队列（D3），
 * 审核通过后源标 merged、覆盖度转入（KbContentService.applyMergeOnPass）。
 */
@Service
public class KbMergeService {

    private static final Logger log = LoggerFactory.getLogger(KbMergeService.class);

    private static final int MAX_CONTENT_CHARS = 12_000;

    private static final int MERGE_MAX_TOKENS = 16_000;

    private final KbContentService contentService;
    private final LlmClient llmClient;

    public KbMergeService(KbContentService contentService, LlmClient llmClient) {
        this.contentService = contentService;
        this.llmClient = llmClient;
    }

    /** AI 合并产物（不落库，回人手里）：追问层/评分要点由服务端在提交时分配稳定 id。 */
    public record AiFollowup(int layer, String direction, String content) {
    }

    public record AiScoringPoint(String level, String content) {
    }

    public record MergeResp(String title, String summary, String frameworkMd, String contentMd,
            List<Variant> variants, List<AiFollowup> followups, List<AiScoringPoint> scoringPoints,
            List<AnswerSample> answerSamples, String difficulty, List<LevelRule> levelRules,
            List<String> conflicts) {
    }

    /** 合并前置校验：2–3 份、同类型同主题、均未下线（下线全链路封死，不能作合并源）。 */
    public List<KpView> validateKp(List<Long> ids) {
        List<Long> distinct = distinctIds(ids);
        requireMergeable("kp", distinct);
        requireSameTopic("kp", distinct);
        return distinct.stream().map(contentService::getKp).toList();
    }

    public List<DocView> validateDoc(List<Long> ids) {
        List<Long> distinct = distinctIds(ids);
        requireMergeable("doc", distinct);
        requireSameTopic("doc", distinct);
        return distinct.stream().map(contentService::getDoc).toList();
    }

    /** AI 一键合并：N 份语料合并生成一份标准产物，多源矛盾点单列不阻断；产物不落库回人手里。 */
    public MergeResp aiMerge(String contentType, List<Long> ids) {
        List<Long> distinct = distinctIds(ids);
        requireMergeable(contentType, distinct);
        requireSameTopic(contentType, distinct);
        String corpus = buildCorpus(contentType, distinct);
        String prompt = PromptTemplate.render(readTemplate("prompts/kb_merge.txt"),
                Map.of("contentType", contentType, "corpus", corpus));
        // 多份语料合并输出全结构产物，分钟级任务：超时放宽，走请求级覆盖（timeoutSeconds）。
        // 输出预算同款坑：思考模型推理 token 计入输出预算，路由默认 4096 会被思考耗尽 → 正文零输出，
        // 显式放宽到 16k（与 SourceAiService 改写同模式），并关闭思考保证 JSON 直接产出。
        MergeResp resp = llmClient.completeJson(LlmRequest.admin(LlmPurpose.KB_MERGE, prompt)
                .timeoutSeconds(com.zhiji.source.service.SourceAiService.JSON_TASK_TIMEOUT_SECONDS)
                .maxTokens(MERGE_MAX_TOKENS)
                .thinking(false)
                .build(), MergeResp.class);
        String difficulty = resp.difficulty() == null ? "" : resp.difficulty().strip();
        if (!List.of("junior", "mid", "senior", "expert").contains(difficulty)) {
            log.info("AI 合并难度建议越界（{}），置空交人工拍板", difficulty);
            difficulty = "";
        }
        List<String> conflicts = resp.conflicts() == null ? List.of()
                : resp.conflicts().stream().filter(c -> c != null && !c.isBlank()).toList();
        return new MergeResp(resp.title(), resp.summary(), resp.frameworkMd(), resp.contentMd(),
                resp.variants(), resp.followups(), resp.scoringPoints(), resp.answerSamples(),
                difficulty, resp.levelRules(), conflicts);
    }

    /** 产物落库：pending 新行 + merge_sources + 审核留痕（source=merge）；结构化字段随产物写入。 */
    @Transactional
    public ContentItemView submitKp(List<Long> ids, MergeResp product) {
        List<Long> distinct = distinctIds(ids);
        requireMergeable("kp", distinct);
        requireSameTopic("kp", distinct);
        KpView first = contentService.getKp(distinct.get(0));
        if (product.difficulty() == null || product.difficulty().isBlank()) {
            throw BizException.validation("合并产物难度需人工拍板后再提交");
        }
        long productId = contentService.createPending("kp", first.domainCode(), first.topicId(),
                product.title(), product.summary(), product.frameworkMd(), product.difficulty(), null,
                "merge", distinct);
        List<Followup> followups = new ArrayList<>();
        if (product.followups() != null) {
            for (AiFollowup f : product.followups()) {
                followups.add(new Followup("fu-" + UUID.randomUUID(), f.layer(), f.direction(), f.content(),
                        "active"));
            }
        }
        List<ScoringPoint> scoringPoints = new ArrayList<>();
        if (product.scoringPoints() != null) {
            for (AiScoringPoint p : product.scoringPoints()) {
                scoringPoints.add(new ScoringPoint("sp-" + UUID.randomUUID(), p.level(), p.content()));
            }
        }
        contentService.updateKp(productId, new UpdateKpReq(null, null, null,
                product.variants(), followups, scoringPoints, product.answerSamples(), null,
                product.levelRules(), null, null, null));
        return contentService.listByTopic(first.topicId(), "kp", null).stream()
                .filter(item -> item.id() == productId)
                .findFirst()
                .orElseThrow(() -> BizException.of(500, "KB_MERGE_FAILED", "合并产物查询失败"));
    }

    @Transactional
    public ContentItemView submitDoc(List<Long> ids, MergeResp product) {
        List<Long> distinct = distinctIds(ids);
        requireMergeable("doc", distinct);
        requireSameTopic("doc", distinct);
        DocView first = contentService.getDoc(distinct.get(0));
        if (product.difficulty() == null || product.difficulty().isBlank()) {
            throw BizException.validation("合并产物难度需人工拍板后再提交");
        }
        long productId = contentService.createPending("doc", first.domainCode(), first.topicId(),
                product.title(), product.summary(), product.contentMd(), product.difficulty(), null,
                "merge", distinct);
        return contentService.listByTopic(first.topicId(), "doc", null).stream()
                .filter(item -> item.id() == productId)
                .findFirst()
                .orElseThrow(() -> BizException.of(500, "KB_MERGE_FAILED", "合并产物查询失败"));
    }

    private void requireMergeable(String contentType, List<Long> ids) {
        if (ids.size() < 2) {
            throw BizException.validation("同位合并至少勾选两份内容");
        }
        if (ids.size() > 3) {
            throw BizException.validation("合并页最多同时勾选三份内容");
        }
        // 下线全链路封死：deprecated/merged 不能作合并源（5.2 边界）
        for (long id : ids) {
            String status = "doc".equals(contentType)
                    ? contentService.requireDoc(id).getStatus()
                    : contentService.requireKp(id).getStatus();
            if ("deprecated".equals(status) || "merged".equals(status)) {
                throw BizException.validation("已下线内容不能参与合并（下线全链路封死）");
            }
        }
    }

    /** 同位合并的前提：同一主题位置之下（同题、同主题的内容天然位于同一位置，重复一眼可见）。 */
    private void requireSameTopic(String contentType, List<Long> ids) {
        Long topicId = null;
        for (long id : ids) {
            long current = "doc".equals(contentType)
                    ? contentService.getDoc(id).topicId()
                    : contentService.getKp(id).topicId();
            if (topicId == null) {
                topicId = current;
            } else if (topicId != current) {
                throw BizException.validation("只能合并同一主题位置下的内容");
            }
        }
    }

    private List<Long> distinctIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.validation("请先勾选要合并的内容");
        }
        return ids.stream().distinct().toList();
    }

    private String buildCorpus(String contentType, List<Long> ids) {
        StringBuilder corpus = new StringBuilder();
        int index = 1;
        for (long id : ids) {
            corpus.append("【语料 ").append(index++).append("】\n");
            if ("kp".equals(contentType)) {
                KpView kp = contentService.getKp(id);
                corpus.append("题面：").append(kp.title()).append('\n');
                if (kp.summary() != null && !kp.summary().isBlank()) {
                    corpus.append("摘要：").append(kp.summary()).append('\n');
                }
                if (!kp.followups().isEmpty()) {
                    corpus.append("追问链：");
                    kp.followups().forEach(f -> corpus.append("(第").append(f.layer()).append("层/")
                            .append(f.direction()).append(")").append(f.content()).append(" "));
                    corpus.append('\n');
                }
                if (!kp.scoringPoints().isEmpty()) {
                    corpus.append("评分要点：");
                    kp.scoringPoints().forEach(p -> corpus.append("[").append(p.level()).append("]")
                            .append(p.content()).append(" "));
                    corpus.append('\n');
                }
                corpus.append(truncate(kp.frameworkMd())).append("\n\n");
            } else {
                DocView doc = contentService.getDoc(id);
                corpus.append("标题：").append(doc.title()).append('\n');
                if (doc.summary() != null && !doc.summary().isBlank()) {
                    corpus.append("摘要：").append(doc.summary()).append('\n');
                }
                corpus.append(truncate(doc.contentMd())).append("\n\n");
            }
        }
        return corpus.toString().strip();
    }

    private String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "（正文为空）";
        }
        String trimmed = text.strip();
        return trimmed.length() > MAX_CONTENT_CHARS
                ? trimmed.substring(0, MAX_CONTENT_CHARS) + "\n（超长截断）"
                : trimmed;
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
