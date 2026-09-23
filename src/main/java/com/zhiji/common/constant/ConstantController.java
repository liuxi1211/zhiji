package com.zhiji.common.constant;

import com.zhiji.common.exception.BizException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 常量组下发接口（公开，免会话）：前端启动时拉全量进 constantsStore，
 * 之后按常量组名称取 code/name 做展示，label 免维护。
 */
@RestController
public class ConstantController {

    private final ConstantRegistry registry;

    public ConstantController(ConstantRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/constants")
    public Map<String, List<ConstantRegistry.Item>> all() {
        return registry.all();
    }

    @GetMapping("/api/constants/{group}")
    public List<ConstantRegistry.Item> group(@PathVariable String group) {
        List<ConstantRegistry.Item> items = registry.items(group);
        if (items == null) {
            throw BizException.notFound("常量组不存在: " + group);
        }
        return items;
    }
}
