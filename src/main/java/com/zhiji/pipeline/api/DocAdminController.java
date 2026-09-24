package com.zhiji.pipeline.api;

import com.zhiji.pipeline.domain.CandidateStatus;
import com.zhiji.pipeline.domain.KpCandidate;
import com.zhiji.pipeline.domain.KpSourceDoc;
import com.zhiji.pipeline.domain.SourceDocStatus;
import com.zhiji.pipeline.domain.SourceForm;
import com.zhiji.pipeline.repo.KpCandidateMapper;
import com.zhiji.pipeline.repo.KpSourceDocMapper;
import com.zhiji.pipeline.service.DocService;
import com.zhiji.pipeline.service.DocService.ImportResult;
import com.zhiji.pipeline.service.ParseService;
import com.zhiji.pipeline.service.SplitService;
import com.zhiji.pipeline.service.SplitService.SplitOutcome;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * B 端素材库接口（/api/admin/docs，员工会话由拦截器统一校验——权限矩阵 /api/admin/** 汇总行）。
 * 导入 → 解析（LLM 形态识别）→ 预览修正 → 拆题入池；候选条目的研判工作流随 T7 在候选池/知识地图展开。
 */
@RestController
@RequestMapping("/api/admin/docs")
public class DocAdminController {

    private final DocService docService;
    private final ParseService parseService;
    private final SplitService splitService;
    private final KpSourceDocMapper docMapper;
    private final KpCandidateMapper candidateMapper;

    public DocAdminController(DocService docService, ParseService parseService, SplitService splitService,
            KpSourceDocMapper docMapper, KpCandidateMapper candidateMapper) {
        this.docService = docService;
        this.parseService = parseService;
        this.splitService = splitService;
        this.docMapper = docMapper;
        this.candidateMapper = candidateMapper;
    }

    public record FormReq(String form) {
    }

    public record SourceRefReq(String sourceRef) {
    }

    public record CandidateUpdateReq(String title, String answerEvidence, String form, String note) {
    }

    @PostMapping("/import")
    public ImportResult importFiles(@RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sourceRef", required = false) String sourceRef) {
        return docService.importFiles(files, sourceRef);
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        return docService.batches();
    }

    @GetMapping("/batches/{batchId}")
    public List<DocView> batchDocs(@PathVariable String batchId) {
        return docMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KpSourceDoc>()
                        .eq(KpSourceDoc::getBatchId, batchId).orderByAsc(KpSourceDoc::getId))
                .stream().map(DocAdminController::toView).toList();
    }

    /** 素材列表（跨批次）：状态 / 形态 / 批次筛选。 */
    @GetMapping
    public List<DocView> list(@RequestParam(required = false) String batchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String form) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KpSourceDoc>()
                .orderByDesc(KpSourceDoc::getId);
        if (batchId != null && !batchId.isBlank()) {
            wrapper.eq(KpSourceDoc::getBatchId, batchId);
        }
        if (status != null && !status.isBlank()) {
            SourceDocStatus.fromCode(status);
            wrapper.eq(KpSourceDoc::getStatus, status);
        }
        if (form != null && !form.isBlank()) {
            SourceForm.fromCode(form);
            wrapper.eq(KpSourceDoc::getForm, form);
        }
        return docMapper.selectList(wrapper).stream().map(DocAdminController::toView).toList();
    }

    /** 文档详情（拆题预览数据源）：解析文本、标题树、名下候选条目。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        KpSourceDoc doc = docMapper.selectById(id);
        if (doc == null) {
            throw com.zhiji.common.exception.BizException.notFound("素材文档不存在：" + id);
        }
        List<CandidateView> candidates = candidateMapper
                .selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KpCandidate>()
                        .eq(KpCandidate::getDocId, id).orderByAsc(KpCandidate::getId))
                .stream().map(DocAdminController::toCandidateView).toList();
        DocView view = new DocView(doc.getId(), doc.getBatchId(), doc.getFileName(), doc.getSourceRef(),
                doc.getForm(), doc.getStatus(), doc.getParseError(),
                doc.getCharCount() == null ? 0 : doc.getCharCount(), doc.getHeadingTree(),
                doc.getRawText(), doc.getCreatedAt(), doc.getUpdatedAt());
        return Map.of("doc", view, "candidates", candidates);
    }

    /** 解析：docparse 抽文本 + LLM 形态识别。 */
    @PostMapping("/{id}/parse")
    public Map<String, Object> parse(@PathVariable long id) {
        parseService.parse(id);
        return Map.of();
    }

    /** 失败重试：清失败态重走解析。 */
    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable long id) {
        parseService.retry(id);
        return Map.of();
    }

    /** 形态修正（解析成功后）。 */
    @PostMapping("/{id}/form")
    public Map<String, Object> changeForm(@PathVariable long id, @RequestBody FormReq req) {
        SourceForm.fromCode(req.form());
        docService.changeForm(id, req.form());
        return Map.of();
    }

    @PutMapping("/{id}/source-ref")
    public Map<String, Object> updateSourceRef(@PathVariable long id, @RequestBody SourceRefReq req) {
        docService.updateSourceRef(id, req.sourceRef());
        return Map.of();
    }

    /** 按当前形态拆题入池（重复拆题先清 pending 候选，幂等）。 */
    @PostMapping("/{id}/split")
    public SplitOutcome split(@PathVariable long id) {
        return splitService.split(id);
    }

    /** 资料型反推出题（按片段勾选触发，D22：反推是 B 端 LLM 成本大头，不默认全量跑）。 */
    @PostMapping("/{id}/inverse-gen")
    public SplitOutcome inverseGen(@PathVariable long id, @RequestBody List<Integer> sectionIndexes) {
        return splitService.inverseGen(id, sectionIndexes);
    }

    /** 候选条目人工修正：题面 / 答案证据 / 片段形态 / 备注（B端管理台PRD 6.3 拆题结果修正）。 */
    @PutMapping("/candidates/{candidateId}")
    public Map<String, Object> updateCandidate(@PathVariable long candidateId,
            @RequestBody CandidateUpdateReq req) {
        KpCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw com.zhiji.common.exception.BizException.notFound("候选条目不存在：" + candidateId);
        }
        if (!CandidateStatus.PENDING.getCode().equals(candidate.getStatus())) {
            throw com.zhiji.common.exception.BizException.conflict("CANDIDATE_NOT_EDITABLE",
                    "只有待研判状态的候选条目可以修正");
        }
        if (req.title() != null && !req.title().isBlank()) {
            candidate.setTitle(req.title().strip());
        }
        if (req.answerEvidence() != null) {
            candidate.setAnswerEvidence(req.answerEvidence().isBlank() ? null : req.answerEvidence().strip());
        }
        if (req.form() != null && !req.form().isBlank()) {
            candidate.setForm(SourceForm.fromCode(req.form()).getCode());
        }
        if (req.note() != null) {
            candidate.setNote(req.note().isBlank() ? null : req.note().strip());
        }
        candidateMapper.updateById(candidate);
        return Map.of();
    }

    /** 放弃单条候选（拆题预览里的删条目——落 discarded 保留记录，不物理删）。 */
    @PostMapping("/candidates/{candidateId}/discard")
    public Map<String, Object> discardCandidate(@PathVariable long candidateId) {
        KpCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw com.zhiji.common.exception.BizException.notFound("候选条目不存在：" + candidateId);
        }
        if (!CandidateStatus.PENDING.getCode().equals(candidate.getStatus())) {
            throw com.zhiji.common.exception.BizException.conflict("CANDIDATE_NOT_EDITABLE",
                    "只有待研判状态的候选条目可以放弃");
        }
        candidate.setStatus(CandidateStatus.DISCARDED.getCode());
        candidateMapper.updateById(candidate);
        return Map.of();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        docService.delete(id);
        return Map.of();
    }

    private static DocView toView(KpSourceDoc doc) {
        return new DocView(doc.getId(), doc.getBatchId(), doc.getFileName(), doc.getSourceRef(),
                doc.getForm(), doc.getStatus(), doc.getParseError(), doc.getCharCount() == null ? 0
                        : doc.getCharCount(), doc.getHeadingTree(), null,
                doc.getCreatedAt(), doc.getUpdatedAt());
    }

    private static CandidateView toCandidateView(KpCandidate candidate) {
        return new CandidateView(candidate.getId(), candidate.getDocId(), candidate.getBatchId(),
                candidate.getTitle(), candidate.getAnswerEvidence(), candidate.getSectionPath(),
                candidate.getForm(), candidate.getStatus(), candidate.getConfidence(), candidate.getNote());
    }

    /**
     * 文档视图：rawText 只在 detail 接口填充（列表视图传 null，避免大字段进列表）；
     * 版权红线：rawText 是过程材料，只进 B 端素材预览，绝不进 kp_* 内容表。
     */
    public record DocView(long id, String batchId, String fileName, String sourceRef, String form,
            String status, String parseError, int charCount, String headingTree, String rawText,
            String createdAt, String updatedAt) {
    }

    public record CandidateView(long id, long docId, String batchId, String title, String answerEvidence,
            String sectionPath, String form, String status, String confidence, String note) {
    }
}
