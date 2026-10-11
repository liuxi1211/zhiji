package com.zhiji.source.api;

import com.zhiji.common.constant.ConstantItem;
import com.zhiji.common.constant.ConstantGroup;
import com.zhiji.source.service.SourceAiService;
import com.zhiji.source.service.SourceService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 素材接口（B 端员工会话，/api/admin/sources）。
 *
 * 消费方分两层（核心模块定义 5.1）：
 * - 素材库页面（辅通道）：承接素材——list / get / create / import / fetch / update（轻量整理）/ discard / delete / ai-rewrite；
 * - 新增向导（主通道，属 5.2 标准知识库模块的前端流程页）：split / split-preview / ai-split / ai-summary /
 *   ai-classify / domain-topics / ai-infer(±save) / submit——概述、难度、分类、拆分、反推与提交入库的
 *   素材侧写入口在此，向导页就绪后直接对接，素材库页面不消费这些端点。
 * AI 五动作是「向导与素材库共用同一套 AI 辅助能力」的后端形态（6.4），产物不落库、永远回到人手里。
 */
@RestController
@RequestMapping("/api/admin/sources")
@Validated
public class SourceController {

    private final SourceService sourceService;
    private final SourceAiService sourceAiService;

    public SourceController(SourceService sourceService, SourceAiService sourceAiService) {
        this.sourceService = sourceService;
        this.sourceAiService = sourceAiService;
    }

    public record CreateReq(
            @NotBlank(message = "标题不能为空") @Size(max = 120, message = "标题长度 120 以内") String title,
            String contentMd,
            String sourceUrl) {
    }

    public record FetchReq(@NotBlank(message = "URL 不能为空") String url) {
    }

    public record UpdateReq(
            @Size(max = 120, message = "标题长度 120 以内") String title,
            String contentMd,
            @Size(max = 200, message = "概述长度 100 字以内") String summary,
            String difficulty,
            String domainCode,
            @Size(max = 60, message = "主题名长度 60 以内") String topicName,
            String sourceUrl) {
    }

    public record SubmitReq(@NotBlank(message = "领域必须人工点选") String domainCode,
            Long topicId, String contentType) {
    }

    public record SplitReq(List<SourceService.SplitSliceInput> slices) {
    }

    public record InferSaveReq(List<SourceAiService.InferResp> inferences) {
    }

    @GetMapping
    public SourceService.SourcePage list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String domainCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return sourceService.page(status, domainCode, keyword, page, pageSize);
    }

    @GetMapping("/{id}")
    public SourceService.SourceItemView get(@PathVariable long id) {
        return sourceService.get(id);
    }

    @GetMapping("/{id}/children")
    public List<SourceService.SourceItemView> children(@PathVariable long id) {
        return sourceService.children(id);
    }

    @GetMapping("/{id}/split-preview")
    public List<String> splitPreview(@PathVariable long id) {
        return sourceService.splitPreview(id);
    }

    @PostMapping
    public SourceService.SourceItemView create(@Validated @RequestBody CreateReq req) {
        return sourceService.create(req.title(), req.contentMd(), req.sourceUrl(), "manual");
    }

    @PostMapping("/import")
    public SourceService.SourceItemView importFile(@RequestPart("file") MultipartFile file) {
        return sourceService.importFile(file);
    }

    @PostMapping("/fetch")
    public SourceService.SourceItemView fetchUrl(@Validated @RequestBody FetchReq req) {
        return sourceService.fetchUrl(req.url());
    }

    @PutMapping("/{id}")
    public SourceService.SourceItemView update(@PathVariable long id, @Validated @RequestBody UpdateReq req) {
        return sourceService.update(id, req.title(), req.contentMd(), req.summary(), req.difficulty(),
                req.domainCode(), req.topicName(), req.sourceUrl());
    }

    @PostMapping("/{id}/split")
    public SourceService.SplitResp split(@PathVariable long id, @RequestBody SplitReq req) {
        return sourceService.split(id, req.slices());
    }

    @PostMapping("/{id}/submit")
    public List<SourceService.SourceItemView> submit(@PathVariable long id,
            @Validated @RequestBody SubmitReq req) {
        return sourceService.submit(id, req.domainCode(), req.topicId(), req.contentType());
    }

    @PostMapping("/{id}/discard")
    public SourceService.SourceItemView discard(@PathVariable long id) {
        return sourceService.discard(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        sourceService.delete(id);
    }

    @GetMapping("/{id}/assets/{fileName}")
    public ResponseEntity<FileSystemResource> asset(@PathVariable long id, @PathVariable String fileName) {
        var path = sourceService.assetPath(id, fileName);
        String lower = fileName.toLowerCase();
        MediaType mediaType = switch (lower.substring(lower.lastIndexOf('.') + 1)) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "svg" -> MediaType.valueOf("image/svg+xml");
            case "webp" -> MediaType.valueOf("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        return ResponseEntity.ok().contentType(mediaType).body(new FileSystemResource(path));
    }

    // —— AI 辅助（产物回人手里，不直接落库） ——

    @PostMapping("/{id}/ai/rewrite")
    public SourceAiService.RewriteResp rewrite(@PathVariable long id) {
        return sourceAiService.rewrite(sourceService.get(id).contentMd());
    }

    /** 流式改写（SSE）：逐片段推送打字机式呈现，长文不再干等；协议见 RewriteStreamSupport。 */
    @PostMapping(value = "/{id}/ai/rewrite/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter rewriteStream(@PathVariable long id) {
        return RewriteStreamSupport.stream(sourceAiService, sourceService.get(id).contentMd());
    }

    @PostMapping("/{id}/ai/split")
    public SourceAiService.SplitResp aiSplit(@PathVariable long id) {
        return sourceAiService.split(sourceService.get(id).contentMd());
    }

    @PostMapping("/{id}/ai/summary")
    public SourceAiService.SummaryResp summarize(@PathVariable long id) {
        return new SourceAiService.SummaryResp(sourceAiService.summarize(sourceService.get(id).contentMd()));
    }

    @PostMapping("/{id}/ai/classify")
    public SourceAiService.ClassifyResp classify(@PathVariable long id, @RequestBody ClassifyReq req) {
        List<String> topics = req.topics() == null ? List.of() : req.topics();
        if (topics.isEmpty()) {
            return new SourceAiService.ClassifyResp("", "候选主题为空");
        }
        return sourceAiService.classify(sourceService.get(id).contentMd(), req.domainCode(), topics);
    }

    /** 难度建议（向导第 3 步：AI 建议四档，人工拍板——概述与难度齐备才能进标准库）。 */
    @PostMapping("/{id}/ai/difficulty")
    public SourceAiService.DifficultyResp difficulty(@PathVariable long id) {
        return sourceAiService.difficulty(sourceService.get(id).contentMd());
    }

    public record ClassifyReq(@NotBlank(message = "领域必须人工点选") String domainCode, List<String> topics) {
    }

    public record InferPreviewResp(List<SourceAiService.InferResp> inferences) {
    }

    @PostMapping("/{id}/ai/infer")
    public InferPreviewResp infer(@PathVariable long id) {
        return new InferPreviewResp(sourceAiService.infer(sourceService.get(id).contentMd()));
    }

    /** 反推预览确认后落库：每条生成面试题素材（source_type=inferred）。 */
    @PostMapping("/{id}/ai/infer/save")
    public List<Long> saveInferred(@PathVariable long id, @RequestBody InferSaveReq req) {
        List<SourceAiService.InferResp> items = req.inferences() == null ? List.of() : req.inferences();
        if (items.isEmpty()) {
            throw new com.zhiji.common.exception.BizException(400, "COMMON_VALIDATION", "没有可保存的面试题");
        }
        return sourceAiService.saveInferred(items, sourceService);
    }

    /** 领域候选主题来源：一期主题清单未建表（T4），由常量组 domain_topics 提供种子候选。 */
    public record DomainTopicsResp(List<String> topics) {
    }

    @GetMapping("/domain-topics")
    public DomainTopicsResp domainTopics(@RequestParam String domainCode) {
        return new DomainTopicsResp(sourceService.topicCandidates(domainCode));
    }
}
