package com.zhiji.pipeline.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.docparse.api.DocParseService;
import com.zhiji.docparse.api.DocParseService.ParsedDoc;
import com.zhiji.llm.api.LlmClient;
import com.zhiji.llm.api.LlmPurpose;
import com.zhiji.llm.api.LlmRequest;
import com.zhiji.pipeline.domain.KpSourceDoc;
import com.zhiji.pipeline.domain.SourceDocStatus;
import com.zhiji.pipeline.domain.SourceForm;
import com.zhiji.pipeline.repo.KpSourceDocMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 素材解析：docparse 抽文本 + 标题树 → LLM 形态识别（知识库PRD 九-2）→ 落解析状态机。
 * 解析失败显式落 parse_failed 与原因（B端管理台PRD 6.2：扫描件明确报错不静默），
 * LLM 形态识别失败不阻塞解析（form 留空待人工指定或重试）。
 */
@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    /** 形态识别输入截断长度：形态判定不需要全文。 */
    private static final int FORM_DETECT_INPUT_LIMIT = 4000;

    private final KpSourceDocMapper docMapper;
    private final DocParseService docParseService;
    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final JsonMapper headingJson = new JsonMapper();

    public ParseService(KpSourceDocMapper docMapper, DocParseService docParseService,
            LlmClient llmClient, PromptLoader promptLoader) {
        this.docMapper = docMapper;
        this.docParseService = docParseService;
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
    }

    /**
     * 解析（非事务方法）：解析失败必须把 parse_failed 状态显式落库（B端管理台PRD 6.2），
     * 若方法在事务内，抛出异常会把「落失败态」的 update 一起回滚——因此拆成多个独立提交的
     * 单语句写库，不用方法级事务（单表 update 自身原子）。
     */
    public void parse(long docId) {
        KpSourceDoc doc = requireDoc(docId);
        if (SourceDocStatus.SPLIT.getCode().equals(doc.getStatus())) {
            throw BizException.conflict("DOC_ALREADY_SPLIT", "该文件已拆题入池，不能重新解析");
        }
        try {
            ParsedDoc parsed = docParseService.parse(doc.getFileName(), doc.getRawText());
            doc.setRawText(parsed.plainText());
            doc.setHeadingTree(headingJson.writeValueAsString(parsed.headings()));
            doc.setCharCount(parsed.charCount());
            doc.setStatus(SourceDocStatus.PARSED.getCode());
            doc.setParseError(null);
            docMapper.updateById(doc);
        } catch (BizException e) {
            doc.setStatus(SourceDocStatus.PARSE_FAILED.getCode());
            doc.setParseError(e.getMessage());
            docMapper.updateById(doc);
            throw e;
        }
        detectForm(doc);
    }

    /** LLM 形态识别：失败只记日志留空 form，人工可改或重试（形态错误可在预览页修正）。 */
    private void detectForm(KpSourceDoc doc) {
        try {
            String content = truncate(doc.getRawText(), FORM_DETECT_INPUT_LIMIT);
            String prompt = promptLoader.render("form_detect", Map.of("content", content));
            String raw = llmClient.complete(LlmRequest.of(LlmPurpose.DOC_FORM_DETECT, null,
                    "你是面试题库内容分析师，只输出 JSON。", prompt));
            Map<String, String> result = ModelJson.parse(raw, new TypeReference<Map<String, String>>() {
            });
            SourceForm.fromCode(result.get("form"));
            doc.setForm(result.get("form"));
            docMapper.updateById(doc);
        } catch (RuntimeException e) {
            log.warn("形态识别失败（docId={}），留空待人工指定：{}", doc.getId(), e.getMessage());
        }
    }

    /** 解析失败后的重试入口：清失败态重走解析。 */
    public void retry(long docId) {
        KpSourceDoc doc = requireDoc(docId);
        if (!SourceDocStatus.PARSE_FAILED.getCode().equals(doc.getStatus())
                && !SourceDocStatus.PENDING.getCode().equals(doc.getStatus())) {
            throw BizException.conflict("DOC_RETRY_INVALID", "只有待解析或解析失败的文件可以重试");
        }
        parse(docId);
    }

    private KpSourceDoc requireDoc(long docId) {
        KpSourceDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw BizException.notFound("素材文档不存在：" + docId);
        }
        return doc;
    }

    private String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
