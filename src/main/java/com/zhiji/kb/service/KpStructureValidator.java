package com.zhiji.kb.service;

import com.zhiji.common.exception.BizException;
import com.zhiji.kb.domain.PointLevel;
import com.zhiji.kb.domain.SubType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点结构校验器：纯逻辑、可脱离 Spring 上下文静态调用（种子数据测试直接 new payload 调用）。
 * 校验失败收集 fields 明细，抛 400 KB_STRUCTURE_INVALID（中文概述 + fields）。
 *
 * 层身份契约：主问题层身份固定 "main"（由题面承载，不在数组内）；追问层 layerKey 形如 "L1"/"L2"，
 * 是稳定身份，永不复用永不修改（数组下标只作展示排序）。
 */
public final class KpStructureValidator {

    private static final Set<String> SAMPLE_KEYS = Set.of("pass", "good", "excellent");

    private static final Set<String> LEVEL_KEYS = Set.of("junior", "mid", "senior", "staff", "expert");

    private static final Set<String> LAYER_STATUS = Set.of("active", "disabled");

    private KpStructureValidator() {
    }

    public record KpSavePayload(Long clusterId, String subType, String title, Integer difficulty,
            List<String> variants, String narrativeFramework, List<FollowupLayer> followups,
            List<RubricGroup> rubric, Map<String, String> samples, Map<String, LevelReq> levelReq,
            List<Long> prereqIds) {
    }

    public record FollowupLayer(String layerKey, String dir, String text, String expect,
            String stopCondition, String status) {
    }

    public record RubricGroup(String layerKey, List<RubricPoint> points) {
    }

    public record RubricPoint(String pointId, String level, String text) {
    }

    public record LevelReq(boolean required, String stopLayer) {
    }

    public static void validate(KpSavePayload payload) {
        Map<String, String> fields = new LinkedHashMap<>();

        if (payload.title() == null || payload.title().isBlank()) {
            fields.put("title", "标题不能为空");
        }
        Integer difficulty = payload.difficulty();
        if (difficulty == null || difficulty < 1 || difficulty > 5) {
            fields.put("difficulty", "难度必须是 1~5 的整数");
        }
        if (payload.subType() == null || !isLegalSubType(payload.subType())) {
            fields.put("subType", "题型取值不合法：" + payload.subType());
        }

        // 引用检查用 {"main"} ∪ 全部追问层（含 disabled）——主问题层由题面承载，也是合法引用目标
        Set<String> layerKeys = collectLayerKeys(payload.followups(), fields);
        Set<String> knownLayerKeys = new LinkedHashSet<>();
        knownLayerKeys.add("main");
        knownLayerKeys.addAll(layerKeys);

        validateVariants(payload.variants(), fields);
        validateRubric(payload.rubric(), knownLayerKeys, fields);
        validateSamples(payload.samples(), fields);
        validateLevelReq(payload.levelReq(), knownLayerKeys, fields);

        if (!fields.isEmpty()) {
            throw BizException.of(400, "KB_STRUCTURE_INVALID",
                    "结构校验未通过，共 " + fields.size() + " 项问题", fields);
        }
    }

    /** 结构合法层集合：{"main"} ∪ followups 全部 layerKey（含 disabled），供 rubric/levelReq 引用检查。 */
    public static Set<String> knownLayerKeys(List<FollowupLayer> followups) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("main");
        if (followups != null) {
            for (FollowupLayer layer : followups) {
                if (layer != null && layer.layerKey() != null && !layer.layerKey().isBlank()) {
                    keys.add(layer.layerKey());
                }
            }
        }
        return keys;
    }

    private static boolean isLegalSubType(String code) {
        try {
            SubType.fromCode(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Set<String> collectLayerKeys(List<FollowupLayer> followups, Map<String, String> fields) {
        Set<String> keys = new LinkedHashSet<>();
        if (followups == null) {
            return keys;
        }
        for (int i = 0; i < followups.size(); i++) {
            FollowupLayer layer = followups.get(i);
            String key = "followups[" + i + "]";
            if (layer == null) {
                fields.put(key, "追问层不能为空");
                continue;
            }
            if (layer.layerKey() == null || layer.layerKey().isBlank()) {
                fields.put(key + ".layerKey", "追问层身份不能为空");
            } else if (!keys.add(layer.layerKey())) {
                fields.put(key + ".layerKey", "追问层身份重复：" + layer.layerKey());
            }
            if ("main".equals(layer.layerKey())) {
                fields.put(key + ".layerKey", "追问层身份不得使用保留值 main");
            }
            if (layer.dir() == null || layer.dir().isBlank()) {
                fields.put(key + ".dir", "追问方向不能为空");
            }
            if (layer.text() == null || layer.text().isBlank()) {
                fields.put(key + ".text", "追问内容不能为空");
            }
            if (!LAYER_STATUS.contains(layer.status())) {
                fields.put(key + ".status", "追问层状态只允许 active/disabled：" + layer.status());
            }
        }
        return keys;
    }

    private static void validateVariants(List<String> variants, Map<String, String> fields) {
        if (variants == null) {
            return;
        }
        if (variants.size() > 5) {
            fields.put("variants", "同题变体最多 5 条，当前 " + variants.size() + " 条");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < variants.size(); i++) {
            String variant = variants.get(i);
            if (variant == null || variant.isBlank()) {
                fields.put("variants[" + i + "]", "变体不能为空白");
            } else if (!seen.add(variant)) {
                fields.put("variants[" + i + "]", "变体重复：" + variant);
            }
        }
    }

    private static void validateRubric(List<RubricGroup> rubric, Set<String> layerKeys,
            Map<String, String> fields) {
        if (rubric == null) {
            return;
        }
        for (int g = 0; g < rubric.size(); g++) {
            RubricGroup group = rubric.get(g);
            String key = "rubric[" + g + "]";
            if (group == null) {
                fields.put(key, "评分组不能为空");
                continue;
            }
            if (group.layerKey() == null || !layerKeys.contains(group.layerKey())) {
                fields.put(key + ".layerKey", "评分组指向未知追问层：" + group.layerKey());
            }
            if (group.points() == null) {
                continue;
            }
            for (int p = 0; p < group.points().size(); p++) {
                RubricPoint point = group.points().get(p);
                String pointKey = key + ".points[" + p + "]";
                if (point == null) {
                    fields.put(pointKey, "评分要点不能为空");
                    continue;
                }
                if (point.level() == null || !isLegalPointLevel(point.level())) {
                    fields.put(pointKey + ".level", "要点级别只允许 basic/mid/senior：" + point.level());
                }
                if (point.text() == null || point.text().isBlank()) {
                    fields.put(pointKey + ".text", "要点内容不能为空白");
                }
            }
        }
    }

    private static boolean isLegalPointLevel(String code) {
        try {
            PointLevel.fromCode(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void validateSamples(Map<String, String> samples, Map<String, String> fields) {
        if (samples == null) {
            return;
        }
        for (String key : samples.keySet()) {
            if (!SAMPLE_KEYS.contains(key)) {
                fields.put("samples." + key, "样例键只允许 pass/good/excellent");
            }
        }
    }

    private static void validateLevelReq(Map<String, LevelReq> levelReq, Set<String> layerKeys,
            Map<String, String> fields) {
        if (levelReq == null) {
            return;
        }
        for (Map.Entry<String, LevelReq> entry : levelReq.entrySet()) {
            String key = entry.getKey();
            if (!LEVEL_KEYS.contains(key)) {
                fields.put("levelReq." + key, "职级键只允许 junior/mid/senior/staff/expert");
                continue;
            }
            LevelReq req = entry.getValue();
            if (req == null) {
                fields.put("levelReq." + key, "职级要求不能为空");
                continue;
            }
            if (req.required() && (req.stopLayer() == null || req.stopLayer().isBlank())) {
                fields.put("levelReq." + key + ".stopLayer", "必答职级必须指定停止层");
            } else if (req.stopLayer() != null && !req.stopLayer().isBlank()
                    && !layerKeys.contains(req.stopLayer())) {
                fields.put("levelReq." + key + ".stopLayer", "停止层指向未知追问层：" + req.stopLayer());
            }
        }
    }
}
