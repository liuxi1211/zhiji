package com.zhiji.demo.api;

import com.zhiji.demo.domain.DemoKp;
import com.zhiji.demo.service.DemoKpService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环境验证接口：验证 SQLite 读写、CHECK 约束、唯一约束、本地缓存。
 * 只服务于"跑通环境"，不属于正式业务接口。
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final DemoKpService service;

    public DemoController(DemoKpService service) {
        this.service = service;
    }

    public record CreateReq(
            @NotBlank String bizId,
            @NotBlank String title,
            String subType,
            @Min(1) @Max(5) Integer difficulty) {
    }

    @PostMapping("/kp")
    public Map<String, Object> create(@Valid @RequestBody CreateReq req) {
        DemoKp kp = new DemoKp();
        kp.setBizId(req.bizId());
        kp.setTitle(req.title());
        kp.setSubType(req.subType() == null ? "default" : req.subType());
        kp.setDifficulty(req.difficulty() == null ? 3 : req.difficulty());
        kp.setFollowups("[]");
        service.save(kp);
        return Map.of("ok", true, "id", kp.getId());
    }

    @GetMapping("/kp/{bizId}")
    public DemoKp get(@PathVariable String bizId) {
        return service.findByBizId(bizId);
    }

    @GetMapping("/kp")
    public List<DemoKp> list() {
        return service.listAll();
    }

    @GetMapping("/cache-stat")
    public Map<String, Object> cacheStat() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dbHitCount", service.dbHitCount());
        m.put("note", "连续请求 /demo/kp/{bizId}，dbHitCount 只在首次增长，说明本地缓存命中");
        return m;
    }

    @DeleteMapping("/cache")
    public Map<String, Object> clearCache() {
        service.clearCache();
        return Map.of("ok", true);
    }
}