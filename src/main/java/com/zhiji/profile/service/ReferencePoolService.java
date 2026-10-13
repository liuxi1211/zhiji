package com.zhiji.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiji.profile.domain.PrfProfile;
import com.zhiji.profile.domain.PrfReferenceClaim;
import com.zhiji.profile.repo.PrfReferenceClaimMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 技能条参考库（Claim Reference Library，PRD v2 5.4）——两层结构：
 * <ul>
 *   <li>单条池（kind=bar）：优秀简历脱敏单条，示范「每一条怎么写」——句式、信息密度、措辞；</li>
 *   <li>整段样板（kind=section）：完整技能部分，按方向×职级打标，示范「一组怎么配」——
 *       条数、能力域划分、深浅搭配与排列顺序，注入时按用户档位过滤。</li>
 * </ul>
 * 注入方式 = 按启用状态整池取用（起步不做向量检索，池子数千条后再上）；
 * 池子质量决定对话起草的上限——入库标准：脱敏、去「精通」式夸大、按方向打标。
 */
@Service
public class ReferencePoolService {

    /**
     * 单条池注入 prompt 的条数上限，控制体量。
     * 现池 151 条（首批种子 51 + 2026-10 扩充 100，Java 后端方向，约 1.2 万字 ≈ 1 万 token 量级），
     * 上限留扩充余量；池子到数千条量级时按 PRD 升级路径改方向/职级分池过滤或向量 RAG，而非继续抬此上限。
     */
    public static final int MAX_POOL = 200;

    /** 整段样板注入上限（条），配合档位过滤控制 token 体量。 */
    public static final int MAX_SECTIONS = 6;

    /** 档位不足时向外扩档的最少样板数（不足则并入相邻档位补齐）。 */
    private static final int MIN_SECTIONS = 3;

    private static final String KIND_BAR = "bar";

    private static final String KIND_SECTION = "section";

    private final PrfReferenceClaimMapper mapper;

    public ReferencePoolService(PrfReferenceClaimMapper mapper) {
        this.mapper = mapper;
    }

    /** 渲染单条池文本（每行一条，带小标题），供 intake_system 模板注入；空池给占位说明。 */
    public String renderBars() {
        List<PrfReferenceClaim> items = mapper.selectList(new LambdaQueryWrapper<PrfReferenceClaim>()
                .eq(PrfReferenceClaim::getKind, KIND_BAR)
                .eq(PrfReferenceClaim::getEnabled, 1)
                .orderByAsc(PrfReferenceClaim::getSortOrder)
                .orderByAsc(PrfReferenceClaim::getId)
                .last("LIMIT " + MAX_POOL));
        if (items.isEmpty()) {
            return "（暂无参考条目）";
        }
        StringBuilder builder = new StringBuilder();
        for (PrfReferenceClaim item : items) {
            builder.append("- ");
            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                builder.append("【").append(item.getTitle().strip()).append("】");
            }
            builder.append(item.getContent().strip()).append('\n');
        }
        return builder.toString().strip();
    }

    /** 渲染整段样板：按用户档位过滤，不足时按档位距离向外扩档补齐。 */
    public String renderSections(PrfProfile profile) {
        String grade = resolveGrade(profile);
        List<PrfReferenceClaim> all = mapper.selectList(new LambdaQueryWrapper<PrfReferenceClaim>()
                .eq(PrfReferenceClaim::getKind, KIND_SECTION)
                .eq(PrfReferenceClaim::getEnabled, 1)
                .orderByAsc(PrfReferenceClaim::getSortOrder)
                .orderByAsc(PrfReferenceClaim::getId));
        if (all.isEmpty()) {
            return "（暂无整段样板）";
        }
        Map<GradeBucket, List<PrfReferenceClaim>> buckets = new EnumMap<>(GradeBucket.class);
        for (PrfReferenceClaim item : all) {
            GradeBucket bucket = GradeBucket.of(item.getGrade());
            if (bucket != null) {
                buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(item);
            }
        }
        GradeBucket target = GradeBucket.of(grade);
        if (target == null) {
            target = GradeBucket.MID;
        }
        List<PrfReferenceClaim> picked = new ArrayList<>(buckets.getOrDefault(target, List.of()));
        for (int distance = 1; distance < GradeBucket.values().length && picked.size() < MIN_SECTIONS; distance++) {
            appendBucket(picked, buckets, target, -distance);
            appendBucket(picked, buckets, target, distance);
        }
        if (picked.isEmpty()) {
            return "（暂无整段样板）";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < picked.size() && i < MAX_SECTIONS; i++) {
            PrfReferenceClaim item = picked.get(i);
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("== ").append(item.getTitle() == null || item.getTitle().isBlank()
                    ? "整段样板" : item.getTitle().strip()).append(" ==\n");
            builder.append(item.getContent().strip());
        }
        return builder.toString().strip();
    }

    private void appendBucket(List<PrfReferenceClaim> picked, Map<GradeBucket, List<PrfReferenceClaim>> buckets,
            GradeBucket target, int offset) {
        int index = target.ordinal() + offset;
        if (index < 0 || index >= GradeBucket.values().length) {
            return;
        }
        picked.addAll(buckets.getOrDefault(GradeBucket.values()[index], List.of()));
    }

    /**
     * 档位推导（与 targetLevel 同口径四档）：targetLevel 优先；未设定按工作年限映射
     * （≤2 年 junior、3~4 年 mid、5~7 年 senior、8 年+ expert；未知默认 mid）。
     */
    public static String resolveGrade(PrfProfile profile) {
        String level = profile == null ? null : profile.getTargetLevel();
        if (level != null) {
            String normalized = level.strip();
            for (GradeBucket bucket : GradeBucket.values()) {
                if (bucket.name().equals(normalized)) {
                    return normalized;
                }
            }
        }
        return deriveFromYears(profile == null ? null : profile.getYears());
    }

    private static String deriveFromYears(Integer years) {
        if (years == null) {
            return GradeBucket.MID.name();
        }
        if (years <= 2) {
            return GradeBucket.JUNIOR.name();
        }
        if (years <= 4) {
            return GradeBucket.MID.name();
        }
        if (years <= 7) {
            return GradeBucket.SENIOR.name();
        }
        return GradeBucket.EXPERT.name();
    }

    /** 职级档位四档（与 JudgeLevel / targetLevel 同口径）。 */
    private enum GradeBucket {
        JUNIOR, MID, SENIOR, EXPERT;

        static GradeBucket of(String value) {
            if (value == null) {
                return null;
            }
            for (GradeBucket bucket : values()) {
                if (bucket.name().equals(value.strip())) {
                    return bucket;
                }
            }
            return null;
        }
    }
}
