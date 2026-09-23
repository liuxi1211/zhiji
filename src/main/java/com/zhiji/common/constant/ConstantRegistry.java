package com.zhiji.common.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 常量组注册表：启动时扫描 com.zhiji 下所有标注 @ConstantGroup 的枚举，注册进内存。
 * 用 ContextRefreshedEvent 保证在所有 Bean 就绪后执行一次；扫描自己签发的类路径即可，
 * 枚举量级小，不做缓存优化。
 */
@Component
public class ConstantRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private final Map<String, List<Item>> groups = new LinkedHashMap<>();
    private boolean loaded = false;

    @Override
    public synchronized void onApplicationEvent(ContextRefreshedEvent event) {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:com/zhiji/**/*.class");
            for (Resource resource : resources) {
                String url = resource.getURL().toString();
                if (url.contains("$")) {
                    continue;
                }
                String fqn = url.substring(url.indexOf("com/zhiji"))
                        .replace('/', '.')
                        .replace(".class", "");
                Class<?> clazz = Class.forName(fqn, false, getClass().getClassLoader());
                if (clazz.isEnum() && clazz.isAnnotationPresent(ConstantGroup.class)) {
                    register(clazz);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("常量组扫描失败", e);
        }
    }

    private void register(Class<?> clazz) {
        String group = clazz.getAnnotation(ConstantGroup.class).value();
        List<Item> items = Arrays.stream(clazz.getEnumConstants())
                .map(item -> (ConstantItem) item)
                .map(item -> new Item(item.getCode(), item.getName()))
                .toList();
        groups.put(group, items);
    }

    public List<Item> items(String group) {
        return groups.get(group);
    }

    public Map<String, List<Item>> all() {
        return Collections.unmodifiableMap(groups);
    }

    public record Item(String code, String name) {
    }
}
