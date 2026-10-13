package com.zhiji.common.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.common.domain.SysConfig;
import com.zhiji.common.exception.BizException;
import com.zhiji.common.repo.SysConfigMapper;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通用配置中心：sys_config（key-value，value 为 JSON）的统一读写层 + volatile 内存快照。
 * 业务代码经 ConfigSection 声明域，经 ConfigFacade 强类型读快照；HTTP 保存走 validate 后落库刷快照，
 * 改动即生效无需重启。单机内存快照与 SQLite 单实例部署匹配，多实例部署时需另做配置同步。
 */
@Service
public class ConfigRegistry implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ConfigRegistry.class);

    private final SysConfigMapper configMapper;
    private final Map<String, ConfigSection<?>> sections;
    private final Map<String, Object> snapshot = new ConcurrentHashMap<>();
    private final JsonMapper jsonMapper = new JsonMapper();

    public ConfigRegistry(SysConfigMapper configMapper, List<ConfigSection<?>> declared) {
        this.configMapper = configMapper;
        this.sections = declared.stream().collect(Collectors.toMap(ConfigSection::key, Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException("配置域 key 重复：" + first.key());
                }, LinkedHashMap::new));
    }

    @Override
    public int getOrder() {
        return 0;
    }

    /** 启动自举：缺 key 落种子默认值；存量数据过 afterLoad 修复（差异回写）后进快照。 */
    @Override
    public void run(ApplicationArguments args) {
        for (ConfigSection<?> section : sections.values()) {
            Object value = readJson(section);
            if (value == null) {
                value = section.defaults();
                writeJson(section.key(), value, section.description());
                log.info("配置域 {} 无存量数据，已写入默认值", section.key());
            } else {
                Object repaired = section.afterLoad(value);
                if (!Objects.equals(repaired, value)) {
                    writeJson(section.key(), repaired, section.description());
                    log.info("配置域 {} 存量数据已归一化", section.key());
                }
                value = repaired;
            }
            snapshot.put(section.key(), value);
        }
        log.info("系统配置已加载：{}", String.join("、", sections.keySet()));
    }

    /** 强类型读快照（各模块门面调用；key 无对应域或未加载返回 null）。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) snapshot.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T defaults(String key) {
        return (T) sectionOf(key).defaults();
    }

    /** 全量视图：schema + 当前值 + 选项池，按 order 排序（GET /api/admin/configs）。 */
    public List<SectionView> views() {
        return sections.values().stream()
                .sorted(Comparator.comparingInt(ConfigSection::order))
                .map(this::viewOf)
                .toList();
    }

    /** HTTP 保存路径：parse → validate → 落库 → 刷快照，返回保存后的域视图。 */
    public SectionView save(String key, JsonNode body) {
        ConfigSection<Object> section = sectionOf(key);
        if (body == null || body.isNull() || body.isMissingNode()) {
            throw BizException.validation("配置 " + key + " 内容不能为空");
        }
        Object value;
        try {
            value = section.parse(body);
        } catch (JacksonException e) {
            throw BizException.of(400, "SYS_PARAM_INVALID",
                    "配置 " + key + " 结构不合法：" + e.getMessage());
        }
        section.validate(value);
        writeJson(key, value, section.description());
        snapshot.put(key, value);
        return viewOf(section);
    }

    /** 类型化保存（跳过 HTTP 校验）：业务/测试以强类型对象直写，语义与旧 LlmConfigService 写路径一致。 */
    public Object saveTyped(String key, Object value) {
        ConfigSection<Object> section = sectionOf(key);
        writeJson(key, value, section.description());
        snapshot.put(key, value);
        return value;
    }

    private ConfigSection<?> sectionRaw(String key) {
        ConfigSection<?> section = sections.get(key);
        if (section == null) {
            throw BizException.of(400, "SYS_PARAM_INVALID", "未知配置域：" + key);
        }
        return section;
    }

    @SuppressWarnings("unchecked")
    private ConfigSection<Object> sectionOf(String key) {
        return (ConfigSection<Object>) sectionRaw(key);
    }

    private SectionView viewOf(ConfigSection<?> section) {
        return new SectionView(section.key(), section.title(), section.description(), section.order(),
                section.fields(), snapshot.get(section.key()), section.sources());
    }

    private Object readJson(ConfigSection<?> section) {
        SysConfig row = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, section.key()));
        if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
            return null;
        }
        try {
            return section.parse(jsonMapper.readTree(row.getConfigValue()));
        } catch (JacksonException e) {
            log.warn("配置域 {} 解析失败，改用默认值：{}", section.key(), e.getMessage());
            return null;
        }
    }

    private void writeJson(String key, Object value, String description) {
        String json = jsonMapper.writeValueAsString(value);
        if (configMapper.updateValue(key, json) == 0) {
            SysConfig config = new SysConfig();
            config.setConfigKey(key);
            config.setConfigValue(json);
            config.setDescription(description);
            configMapper.insert(config);
        }
    }
}
