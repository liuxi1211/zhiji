package com.zhiji.kb.api;

import com.zhiji.kb.service.KpService;
import com.zhiji.kb.service.KpService.KpDetailView;
import com.zhiji.kb.service.KpService.KpMetaView;
import com.zhiji.kb.service.KpStructureValidator.KpSavePayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * B 端知识点管理（/api/admin/kp，员工会话由拦截器统一校验）。
 * 追问层身份是红线：新增/停用层走专属接口，PUT 不允许增删 layerKey。
 */
@RestController
@RequestMapping("/api/admin/kp")
public class KpAdminController {

    private final KpService kpService;

    public KpAdminController(KpService kpService) {
        this.kpService = kpService;
    }

    public record AddLayerReq(@NotBlank(message = "追问方向不能为空") String dir,
            @NotBlank(message = "追问内容不能为空") String text, String expect, String stopCondition) {
    }

    public record StatusReq(@NotBlank(message = "状态不能为空")
    @Pattern(regexp = "reviewed|active", message = "状态取值不合法") String status) {
    }

    public record RetireReq(@NotBlank(message = "下线方式不能为空")
    @Pattern(regexp = "discard|merge", message = "下线方式取值不合法") String mode, Long targetId) {
    }

    public record FreqSignalReq(@NotBlank(message = "频率档位不能为空")
    @Pattern(regexp = "high|mid|low", message = "频率档位取值不合法") String signalLevel,
            @NotBlank(message = "标定说明不能为空") String note) {
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(required = false) String subType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer difficulty,
            @RequestParam(required = false)
            @Pattern(regexp = "marked|unmarked", message = "baseline 取值不合法") String baseline,
            @RequestParam(required = false) Boolean reviewDue,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return kpService.list(keyword, domainId, clusterId, subType, status, difficulty, baseline,
                reviewDue, safePage, safeSize);
    }

    @GetMapping("/meta")
    public KpMetaView meta() {
        return kpService.meta();
    }

    @GetMapping("/{id}")
    public KpDetailView detail(@PathVariable long id) {
        return kpService.detail(id);
    }

    @PostMapping
    public Map<String, Long> create(@RequestBody KpSavePayload payload) {
        return Map.of("id", kpService.create(payload));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody KpSavePayload payload) {
        kpService.update(id, payload);
        return Map.of();
    }

    @PostMapping("/{id}/followup-layers")
    public Map<String, String> addFollowupLayer(@PathVariable long id, @Valid @RequestBody AddLayerReq req) {
        return Map.of("layerKey", kpService.addFollowupLayer(id, req.dir(), req.text(), req.expect(),
                req.stopCondition()));
    }

    @PostMapping("/{id}/followup-layers/{layerKey}/disable")
    public Map<String, Object> disableLayer(@PathVariable long id, @PathVariable String layerKey) {
        kpService.disableLayer(id, layerKey);
        return Map.of();
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable long id, @Valid @RequestBody StatusReq req) {
        kpService.changeStatus(id, req.status());
        return Map.of();
    }

    @PostMapping("/{id}/retire")
    public Map<String, Object> retire(@PathVariable long id, @Valid @RequestBody RetireReq req) {
        kpService.retire(id, req.mode(), req.targetId());
        return Map.of();
    }

    @PutMapping("/{id}/freq-signal")
    public Map<String, Object> saveFreqSignal(@PathVariable long id,
            @Valid @RequestBody FreqSignalReq req) {
        kpService.saveFreqSignal(id, req.signalLevel(), req.note());
        return Map.of();
    }
}
