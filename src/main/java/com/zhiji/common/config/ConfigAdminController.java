package com.zhiji.common.config;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 通用系统配置接口（/api/admin/configs，仅 admin——权限矩阵见基础架构PRD 3.3）。
 * GET 下发全部域的 schema+值+选项池，PUT 按域整体保存（后端域级校验），前端自渲染零逐域开发。
 * 含密钥的域（llm.channel）经 ConfigSection.masked 脱敏下发、merge 还原入库——真实密钥不出后端。
 */
@RestController
@RequestMapping("/api/admin/configs")
public class ConfigAdminController {

    private final ConfigRegistry registry;

    public ConfigAdminController(ConfigRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<SectionView> list() {
        return registry.views();
    }

    @PutMapping("/{key}")
    public SectionView update(@PathVariable String key, @RequestBody JsonNode body) {
        return registry.save(key, body);
    }
}
