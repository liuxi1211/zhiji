package com.zhiji.kb.api;

import com.zhiji.kb.service.ClusterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端知识库簇管理（/api/admin/clusters，员工会话由拦截器统一校验）。
 * status/merged_into 不开放给 PUT——状态只走 status 接口，下线只走合并。
 */
@RestController
@RequestMapping("/api/admin/clusters")
public class ClusterAdminController {

    private final ClusterService clusterService;

    public ClusterAdminController(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    public record CreateReq(@NotNull(message = "技能域不能为空") Long domainId,
            @NotBlank(message = "簇名不能为空") String name, String description) {
    }

    public record UpdateReq(String name, Long domainId, String description, String zeroShotMd,
            String referenceSolutionMd) {
    }

    public record StatusReq(@NotBlank(message = "状态不能为空")
    @Pattern(regexp = "reviewed|active", message = "状态取值不合法") String status) {
    }

    public record MergeReq(@NotNull(message = "合并目标不能为空") Long targetId) {
    }

    @GetMapping
    public Map<String, List<ClusterService.ClusterView>> list(
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) String status) {
        return Map.of("list", clusterService.list(domainId, status));
    }

    @GetMapping("/{id}")
    public ClusterService.ClusterDetailView detail(@PathVariable long id) {
        return clusterService.detail(id);
    }

    @PostMapping
    public Map<String, Long> create(@Valid @RequestBody CreateReq req) {
        return Map.of("id", clusterService.create(req.domainId(), req.name(), req.description()));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody UpdateReq req) {
        clusterService.update(id, req.name(), req.domainId(), req.description(), req.zeroShotMd(),
                req.referenceSolutionMd());
        return Map.of();
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable long id, @Valid @RequestBody StatusReq req) {
        clusterService.changeStatus(id, req.status());
        return Map.of();
    }

    @PostMapping("/{id}/merge")
    public Map<String, Object> merge(@PathVariable long id, @Valid @RequestBody MergeReq req) {
        clusterService.merge(id, req.targetId());
        return Map.of();
    }
}
