package com.zhiji.llm.config;

import com.zhiji.common.config.ConfigSection;
import com.zhiji.common.config.FieldDef;
import com.zhiji.common.exception.BizException;
import com.zhiji.llm.domain.LlmPromptConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置域「提示词模板」（llm.prompt）：模板 code → 文本。
 * 首次启动扫描 classpath prompts/*.txt 种子入库；运行时以库为准（PromptStore），
 * classpath 文件保留作「恢复默认」参照（GET /api/admin/settings/prompt-defaults）。
 *
 * 升级并入：新版本新增模板文件时，afterLoad 把缺失的 code 补进存量库（只补缺，不动已编辑的）。
 * fields() 为空：本域由 LLM 管理页「提示词模板」tab 定制渲染（列表 + 编辑器）。
 */
@Component
public class LlmPromptSection implements ConfigSection<LlmPromptConfig> {

    public static final String KEY = "llm.prompt";

    private static final String CLASSPATH_PATTERN = "classpath*:prompts/*.txt";

    /** 模板展示名（code → 中文名），随 sources 下发（llm.promptNames）。 */
    public static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("judge", "试判判定"),
            Map.entry("intake_system", "建档对话 · 系统提示"),
            Map.entry("intake_first", "建档对话 · 开场白"),
            Map.entry("claim_anchor_map", "主张锚点映射"),
            Map.entry("kb_merge", "知识合并"),
            Map.entry("source_rewrite", "素材改写"),
            Map.entry("source_title", "素材标题"),
            Map.entry("source_followup", "素材追问"),
            Map.entry("source_summary", "素材摘要"),
            Map.entry("source_classify", "素材分类"),
            Map.entry("source_locate", "领域主题定位"),
            Map.entry("source_infer", "素材推断"),
            Map.entry("source_difficulty", "难度标定"));

    private final JsonMapper jsonMapper = new JsonMapper();

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return "提示词模板";
    }

    @Override
    public String description() {
        return "各业务入口的提示词模板，保存后立即生效；classpath 文件保留为恢复默认参照";
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public List<FieldDef> fields() {
        // 值结构由 LLM 管理页「提示词模板」tab 定制渲染；schema 事实源是 LlmPromptConfig record
        return List.of();
    }

    /** 首启种子：扫描 classpath prompts/*.txt（code = 文件名去扩展名）。 */
    @Override
    public LlmPromptConfig defaults() {
        return new LlmPromptConfig(scanClasspath());
    }

    @Override
    public LlmPromptConfig parse(JsonNode json) {
        return jsonMapper.treeToValue(json, LlmPromptConfig.class);
    }

    /** 只做轻校验：非空 + {{变量}} 占位符语法合法。模板正文常含 JSON 示例（花括号嵌套），不数全局括号；变量集是否够用交由业务回归验证。 */
    @Override
    public void validate(LlmPromptConfig config) {
        if (config.templates().isEmpty()) {
            throw BizException.validation("提示词模板不能为空");
        }
        for (Map.Entry<String, String> entry : config.templates().entrySet()) {
            String code = entry.getKey();
            String text = entry.getValue();
            if (text == null || text.isBlank()) {
                throw BizException.of(400, "SYS_PARAM_INVALID", "模板 " + code + " 内容不能为空");
            }
            String stripped = text.replaceAll("\\{\\{\\s*[^{}\\s]+\\s*\\}\\}", "");
            if (stripped.contains("{{")) {
                throw BizException.of(400, "SYS_PARAM_INVALID",
                        "模板 " + code + " 存在未配对或空的 {{变量}} 占位符");
            }
        }
    }

    /** 存量库补新模板：只并入库中缺失的 code，管理员编辑过的模板一律不动。 */
    @Override
    public Object afterLoad(Object loaded) {
        LlmPromptConfig current = (LlmPromptConfig) loaded;
        Map<String, String> merged = new LinkedHashMap<>(scanClasspath());
        merged.putAll(current.templates());
        return new LlmPromptConfig(merged);
    }

    @Override
    public Map<String, Object> sources() {
        return Map.of("llm.promptNames", NAMES);
    }

    /** 扫描 classpath prompts/*.txt；打包为 fat jar 后同样可读（BOOT-INF/classes）。 */
    public static Map<String, String> scanClasspath() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(CLASSPATH_PATTERN);
            Map<String, String> templates = new LinkedHashMap<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".txt")) {
                    continue;
                }
                String code = filename.substring(0, filename.length() - 4);
                templates.put(code, new String(resource.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            }
            if (templates.isEmpty()) {
                throw new IllegalStateException("classpath 未找到任何 prompts/*.txt 模板");
            }
            return templates;
        } catch (IOException e) {
            throw new UncheckedIOException("扫描提示词模板失败：" + CLASSPATH_PATTERN, e);
        }
    }
}
