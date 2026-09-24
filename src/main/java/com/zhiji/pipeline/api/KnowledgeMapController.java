package com.zhiji.pipeline.api;

import com.zhiji.pipeline.service.CanonicalService;
import com.zhiji.pipeline.service.CanonicalService.CanonicalDetail;
import com.zhiji.pipeline.service.CanonicalService.CanonicalItem;
import com.zhiji.pipeline.service.CanonicalService.CoverageView;
import com.zhiji.pipeline.service.CanonicalService.NameSuggestion;
import com.zhiji.pipeline.service.CanonicalService.RunSummary;
import com.zhiji.pipeline.service.CanonicalService.TreeView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端知识地图接口（/api/admin/knowledge-map，员工会话由拦截器统一校验——权限矩阵 /api/admin/** 汇总行）。
 * 语料归一（D21）：三层漏斗运行、三层视图、归一确认 / 拆分回退、簇操作编排、覆盖率统计。
 * 簇改名 / 域调整走既有 /api/admin/clusters（kb），这里只做需要编排归一题归属的操作。
 */
@RestController
@RequestMapping("/api/admin/knowledge-map")
public class KnowledgeMapController {

    private final CanonicalService canonicalService;

    public KnowledgeMapController(CanonicalService canonicalService) {
        this.canonicalService = canonicalService;
    }

    public record ConfirmReq(Long clusterId) {
    }

    public record SplitReq(@NotBlank(message = "拆分方式不能为空")
    @Pattern(regexp = "all|partial", message = "拆分方式只支持 all / partial") String mode,
            List<Long> candidateIds) {
    }

    public record MoveClusterReq(Long clusterId) {
    }

    public record MergeClusterReq(Long targetId) {
    }

    /** 运行增量归一（结构投票 → embedding 召回 → LLM 精判 → 语料簇聚类与命名）。 */
    @PostMapping("/run")
    public RunSummary run() {
        return canonicalService.run();
    }

    @GetMapping("/coverage")
    public CoverageView coverage() {
        return canonicalService.coverage();
    }

    @GetMapping("/tree")
    public TreeView tree() {
        return canonicalService.tree();
    }

    /** 归一题列表：unclustered=true 查未归簇桶；clusterId 与 unclustered 互斥。 */
    @GetMapping("/canonicals")
    public Map<String, List<CanonicalItem>> canonicals(@RequestParam(required = false) Long clusterId,
            @RequestParam(required = false) Boolean unclustered,
            @RequestParam(required = false) String status) {
        return Map.of("list", canonicalService.canonicals(clusterId, Boolean.TRUE.equals(unclustered), status));
    }

    @GetMapping("/canonicals/{id}")
    public CanonicalDetail detail(@PathVariable long id) {
        return canonicalService.detail(id);
    }

    /** 确认同题（pending → confirmed），可顺带调整簇归属。 */
    @PostMapping("/canonicals/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable long id, @RequestBody(required = false) ConfirmReq req) {
        canonicalService.confirm(id, req == null ? null : req.clusterId());
        return Map.of();
    }

    /** 解除知识点映射（自动映射纠错口）。 */
    @PostMapping("/canonicals/{id}/unbuild")
    public Map<String, Object> unbuild(@PathVariable long id) {
        canonicalService.unbuild(id);
        return Map.of();
    }

    /** 拆分回退：误归并可整题解散或拆出部分候选（退回候选池散题）。 */
    @PostMapping("/canonicals/{id}/split")
    public Map<String, Object> split(@PathVariable long id, @jakarta.validation.Valid @RequestBody SplitReq req) {
        return canonicalService.split(id, req.mode(), req.candidateIds());
    }

    /** 调整簇归属（clusterId 为空 = 移出簇）。 */
    @PostMapping("/canonicals/{id}/cluster")
    public Map<String, Object> moveCluster(@PathVariable long id, @RequestBody MoveClusterReq req) {
        canonicalService.moveCluster(id, req.clusterId());
        return Map.of();
    }

    /** 语料簇合并编排：先移归一题归属再 retire 源簇。 */
    @PostMapping("/clusters/{id}/merge")
    public Map<String, Object> mergeClusters(@PathVariable long id, @jakarta.validation.Valid @RequestBody MergeClusterReq req) {
        if (req.targetId() == null) {
            throw com.zhiji.common.exception.BizException.validation("合并目标不能为空");
        }
        canonicalService.mergeClusters(id, req.targetId());
        return Map.of();
    }

    /** AI 簇命名建议（只建议不落库，采纳走既有 PUT /api/admin/clusters/{id}）。 */
    @PostMapping("/clusters/{id}/suggest-name")
    public NameSuggestion suggestName(@PathVariable long id) {
        return canonicalService.suggestName(id);
    }
}
