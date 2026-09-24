package com.zhiji.pipeline.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.docparse.api.DocParseService.Heading;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.pipeline.domain.KpCandidate;
import com.zhiji.pipeline.domain.KpSourceDoc;
import com.zhiji.pipeline.domain.SourceDocStatus;
import com.zhiji.pipeline.domain.SourceForm;
import com.zhiji.pipeline.repo.KpCandidateMapper;
import com.zhiji.pipeline.repo.KpSourceDocMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * 三路拆分（知识库PRD 九-2 / 决策清单 D22）：按内容形态路由——
 * 题库型：标题层级 → 题号正则 → LLM 兜底三档策略，做题-答配对；
 * 资料型：按标题树切章节片段，反推出题按片段勾选触发（B 端 LLM 成本大头，不默认全量跑）；
 * 面经型：LLM 抽题 + 追问记录。
 * 三路汇成统一候选条目（status=pending），拆题产物距用户路径还隔研判、成稿、校对三道关。
 */
@Service
public class SplitService {

    private static final Logger log = LoggerFactory.getLogger(SplitService.class);

    /** 题号正则（题库型第二档策略）：1. / 1、 / 第1题 / Q1 / 【题1】等行首题号。 */
    private static final Pattern QUESTION_NUMBER =
            Pattern.compile("^\\s*(?:\\d{1,3}\\s*[.、:：)]|第\\s*\\d{1,3}\\s*[题问]|Q\\s*\\d{1,3}|【题\\s*\\d{1,3}】|问题\\s*\\d{1,3})\\s*");

    /** 单次 LLM 调用输入截断：防超长与成本失控。 */
    private static final int LLM_INPUT_LIMIT = 6000;

    private static final String SYSTEM_JSON_ONLY = "你是面试题库整理助手，只输出 JSON。";

    private final KpSourceDocMapper docMapper;
    private final KpCandidateMapper candidateMapper;
    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    public SplitService(KpSourceDocMapper docMapper, KpCandidateMapper candidateMapper,
            LlmClient llmClient, PromptLoader promptLoader) {
        this.docMapper = docMapper;
        this.candidateMapper = candidateMapper;
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
    }

    /** 拆题结果（题库型/面经型路径产物）；资料型产物走 inverseGen 逐片段生成。 */
    public record SplitItem(String title, String answer, String sectionPath, String confidence) {
    }

    public record SplitOutcome(int inserted, List<SplitItem> items) {
    }

    /** 按当前形态执行拆题；重复拆题先清空该文档的 pending 候选（幂等）。 */
    @Transactional
    public SplitOutcome split(long docId) {
        KpSourceDoc doc = requireDoc(docId);
        if (!SourceDocStatus.PARSED.getCode().equals(doc.getStatus())
                && !SourceDocStatus.SPLIT.getCode().equals(doc.getStatus())) {
            throw BizException.conflict("DOC_NOT_PARSED", "只有解析成功的文件可以拆题，请先执行解析");
        }
        if (doc.getForm() == null || doc.getForm().isBlank()) {
            throw BizException.validation("请先指定内容形态（自动识别失败时在预览页人工指定）");
        }
        candidateMapper.deletePendingByDocId(docId);

        SourceForm form = SourceForm.fromCode(doc.getForm());
        List<SplitItem> items = switch (form) {
            case QUESTION_BANK -> splitQuestionBank(doc);
            case READING -> splitReading(doc);
            case INTERVIEW -> splitInterview(doc);
        };
        int inserted = 0;
        for (SplitItem item : items) {
            KpCandidate candidate = new KpCandidate();
            candidate.setDocId(docId);
            candidate.setBatchId(doc.getBatchId());
            candidate.setTitle(item.title());
            candidate.setAnswerEvidence(item.answer());
            candidate.setSectionPath(item.sectionPath());
            candidate.setForm(form.getCode());
            candidate.setStatus(com.zhiji.pipeline.domain.CandidateStatus.PENDING.getCode());
            candidate.setConfidence(item.confidence());
            candidateMapper.insert(candidate);
            inserted++;
        }
        doc.setStatus(SourceDocStatus.SPLIT.getCode());
        docMapper.updateById(doc);
        return new SplitOutcome(inserted, items);
    }

    /** 题库型三档：①标题层级 → ②题号正则 → ③LLM 兜底（confidence 标记档位来源）。 */
    private List<SplitItem> splitQuestionBank(KpSourceDoc doc) {
        List<SplitItem> byHeading = splitByHeadings(doc);
        if (!byHeading.isEmpty()) {
            return byHeading;
        }
        List<SplitItem> byNumber = splitByQuestionNumbers(doc);
        if (!byNumber.isEmpty()) {
            return byNumber;
        }
        return splitByLlm(doc, "split_fallback", LlmPurpose.SPLIT_FALLBACK, raw -> {
            Map<String, Object> parsed = ModelJson.parse(raw, new TypeReference<Map<String, Object>>() {
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) parsed.get("items");
            List<SplitItem> result = new ArrayList<>();
            if (items == null) {
                return result;
            }
            for (Map<String, Object> item : items) {
                String question = textOf(item.get("question"));
                if (!question.isBlank()) {
                    result.add(new SplitItem(question, textOf(item.get("answer")), null, "low"));
                }
            }
            return result;
        });
    }

    /** 资料型：按标题树切章节片段（每片段一条 reading 候选，answer=片段正文），反推出题另行按需触发。 */
    private List<SplitItem> splitReading(KpSourceDoc doc) {
        List<Heading> headings = readHeadings(doc);
        if (headings.isEmpty()) {
            return List.of(new SplitItem(doc.getFileName(), doc.getRawText(), null, "high"));
        }
        String[] lines = doc.getRawText().split("\n", -1);
        List<SplitItem> result = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int start = heading.line();
            int end = i + 1 < headings.size() ? headings.get(i + 1).line() : lines.length;
            StringBuilder body = new StringBuilder();
            for (int line = start; line < end && line < lines.length; line++) {
                body.append(lines[line]).append('\n');
            }
            String text = body.toString().strip();
            if (!text.isBlank()) {
                result.add(new SplitItem(heading.title(), text, heading.title(), "high"));
            }
        }
        return result;
    }

    /** 面经型：LLM 抽题 + 追问记录，每题一条候选。 */
    private List<SplitItem> splitInterview(KpSourceDoc doc) {
        return splitByLlm(doc, "interview_extract", LlmPurpose.INTERVIEW_EXTRACT, raw -> {
            Map<String, Object> parsed = ModelJson.parse(raw, new TypeReference<Map<String, Object>>() {
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) parsed.get("questions");
            List<SplitItem> result = new ArrayList<>();
            if (items == null) {
                return result;
            }
            for (Map<String, Object> item : items) {
                String question = textOf(item.get("question"));
                if (question.isBlank()) {
                    continue;
                }
                Object followups = item.get("followups");
                String evidence = followups instanceof List<?> list && !list.isEmpty()
                        ? "追问记录：" + String.join("；", list.stream().map(String::valueOf).toList())
                        : null;
                result.add(new SplitItem(question, evidence, null, "low"));
            }
            return result;
        });
    }

    /**
     * 资料型反推出题（按片段勾选触发，B端管理台PRD 6.3）：对指定章节片段生成面试问法与答案要点，
     * 每问一条 reading 候选。
     */
    @Transactional
    public SplitOutcome inverseGen(long docId, List<Integer> sectionIndexes) {
        KpSourceDoc doc = requireDoc(docId);
        if (!SourceForm.READING.getCode().equals(doc.getForm())) {
            throw BizException.validation("只有资料型文档可以反推出题");
        }
        List<Heading> headings = readHeadings(doc);
        List<SplitItem> fragments = splitReading(doc);
        List<SplitItem> generated = new ArrayList<>();
        for (int index : sectionIndexes) {
            if (index < 0 || index >= fragments.size()) {
                throw BizException.validation("片段索引越界：" + index);
            }
            SplitItem fragment = fragments.get(index);
            String prompt = promptLoader.render("inverse_question",
                    Map.of("section", fragment.sectionPath() == null ? "" : fragment.sectionPath(),
                            "content", truncate(fragment.answer() == null ? "" : fragment.answer())));
            List<SplitItem> items = callLlmForItems(prompt, LlmPurpose.QUESTION_GEN, raw -> {
                Map<String, Object> parsed = ModelJson.parse(raw, new TypeReference<Map<String, Object>>() {
                });
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> questions = (List<Map<String, Object>>) parsed.get("questions");
                List<SplitItem> result = new ArrayList<>();
                if (questions == null) {
                    return result;
                }
                for (Map<String, Object> item : questions) {
                    String question = textOf(item.get("question"));
                    if (question.isBlank()) {
                        continue;
                    }
                    Object points = item.get("points");
                    String evidence = points instanceof List<?> list && !list.isEmpty()
                            ? String.join("\n", list.stream().map(String::valueOf).toList())
                            : null;
                    result.add(new SplitItem(question, evidence, fragment.sectionPath(), "low"));
                }
                return result;
            });
            generated.addAll(items);
        }
        int inserted = 0;
        for (SplitItem item : generated) {
            KpCandidate candidate = new KpCandidate();
            candidate.setDocId(docId);
            candidate.setBatchId(doc.getBatchId());
            candidate.setTitle(item.title());
            candidate.setAnswerEvidence(item.answer());
            candidate.setSectionPath(item.sectionPath());
            candidate.setForm(SourceForm.READING.getCode());
            candidate.setStatus(com.zhiji.pipeline.domain.CandidateStatus.PENDING.getCode());
            candidate.setConfidence(item.confidence());
            candidateMapper.insert(candidate);
            inserted++;
        }
        if (!SourceDocStatus.SPLIT.getCode().equals(doc.getStatus())) {
            doc.setStatus(SourceDocStatus.SPLIT.getCode());
            docMapper.updateById(doc);
        }
        return new SplitOutcome(inserted, generated);
    }

    /** 通用 LLM 拆题调用：prompt 渲染 → complete → 围栏剥离 → 契约解析 → SplitItem 映射。 */
    private List<SplitItem> splitByLlm(KpSourceDoc doc, String promptName, LlmPurpose purpose,
            java.util.function.Function<String, List<SplitItem>> mapper) {
        String prompt = promptLoader.render(promptName, Map.of("content", truncate(doc.getRawText())));
        return callLlmForItems(prompt, purpose, mapper);
    }

    private List<SplitItem> callLlmForItems(String prompt, LlmPurpose purpose,
            java.util.function.Function<String, List<SplitItem>> mapper) {
        String raw = llmClient.complete(LlmRequest.user(purpose, 0L, prompt));
        List<SplitItem> items = mapper.apply(raw);
        if (items.isEmpty()) {
            log.warn("LLM 拆题结果为空（purpose={}）", purpose);
        }
        return items;
    }

    /** 标题层级拆题：H2/H3 为题、其后正文为答（confidence=high）。 */
    private List<SplitItem> splitByHeadings(KpSourceDoc doc) {
        List<Heading> headings = readHeadings(doc).stream()
                .filter(h -> h.level() == 2 || h.level() == 3)
                .toList();
        if (headings.size() < 2) {
            // 少于 2 个题级标题不足以构成题库结构
            return List.of();
        }
        String[] lines = doc.getRawText().split("\n", -1);
        List<SplitItem> result = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int start = heading.line() + 1;
            int end = i + 1 < headings.size() ? headings.get(i + 1).line() : lines.length;
            StringBuilder answer = new StringBuilder();
            for (int line = start; line < end && line < lines.length; line++) {
                answer.append(lines[line]).append('\n');
            }
            result.add(new SplitItem(heading.title(), answer.toString().strip(), heading.title(), "high"));
        }
        return result;
    }

    /** 题号正则拆题：题号行起题、到下一题号行为止为答（confidence=high）。 */
    private List<SplitItem> splitByQuestionNumbers(KpSourceDoc doc) {
        String[] lines = doc.getRawText().split("\n", -1);
        List<Integer> questionLines = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (QUESTION_NUMBER.matcher(lines[i]).find() && lines[i].strip().length() >= 6) {
                questionLines.add(i);
            }
        }
        if (questionLines.size() < 2) {
            return List.of();
        }
        List<SplitItem> result = new ArrayList<>();
        for (int i = 0; i < questionLines.size(); i++) {
            int start = questionLines.get(i);
            int end = i + 1 < questionLines.size() ? questionLines.get(i + 1) : lines.length;
            StringBuilder body = new StringBuilder();
            for (int line = start; line < end && line < lines.length; line++) {
                body.append(lines[line]).append('\n');
            }
            String text = body.toString().strip();
            if (text.isBlank()) {
                continue;
            }
            Matcher matcher = QUESTION_NUMBER.matcher(text);
            String title = matcher.find() ? text.substring(matcher.end()).strip() : text;
            int newline = title.indexOf('\n');
            if (newline > 0) {
                String firstLine = title.substring(0, newline).strip();
                String rest = title.substring(newline).strip();
                result.add(new SplitItem(firstLine, rest, null, "high"));
            } else {
                result.add(new SplitItem(title, null, null, "high"));
            }
        }
        return result;
    }

    private List<Heading> readHeadings(KpSourceDoc doc) {
        if (doc.getHeadingTree() == null || doc.getHeadingTree().isBlank()) {
            return List.of();
        }
        return ModelJson.parse(doc.getHeadingTree(), new TypeReference<List<Heading>>() {
        });
    }

    private KpSourceDoc requireDoc(long docId) {
        KpSourceDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw BizException.notFound("素材文档不存在：" + docId);
        }
        return doc;
    }

    private String truncate(String text) {
        return text == null ? "" : text.length() <= LLM_INPUT_LIMIT ? text : text.substring(0, LLM_INPUT_LIMIT);
    }

    private String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
