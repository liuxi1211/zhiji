package com.zhiji.judge.service;

import com.zhiji.judge.domain.Grade;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 档位公式（核心模块定义 6.1 硬规则 3，D3 严格制）：单知识点四档全部由本类从命中分布计算，
 * 模型从不碰分。纯静态无依赖——规则变更走版本管理不走模型。
 *
 * 公式（按序裁决）：
 * 1. 题面未答 → 无法评级（grade=null）；
 * 2. 任一 in-scope 作答 wrong → fail（硬否决：面试里说反概念的挂）；
 * 3. 及格级（pass）要点集合为空 → 无法评级（内容标定缺陷，试判台负责暴露）；
 * 4. pass 全命中 → pass；pass+good 全命中 → good；再加 excellent ≥1 命中 → excellent；
 *    否则 fail（讲得浅的过不了更高档，但已保住及格）。
 *
 * 命中跨问累计（D5）：评分要点是知识点级的，某要点在任一 in-scope 作答中命中即计为命中。
 * in-scope 过滤（停止点之外层不计入档位）由调用方完成，本类只看传入的作答行。
 */
public final class GradeCalculator {

    private GradeCalculator() {
    }

    /** 单条要点命中事实：来自作答行的 points_result 快照。 */
    public record PointHit(String id, String level, boolean hit) {
    }

    /** 单次作答的判定结果。 */
    public record AnswerRow(String verdict, List<PointHit> points) {
    }

    /** 知识点全量评分要点（id + 档位），判断"该命中什么"的尺子。 */
    public record PointSpec(String id, String level) {
    }

    /** 档位结果：grade=null 表示无法评级（note 说明原因）。 */
    public record GradeResult(Grade grade, String note) {
    }

    public static GradeResult calculate(List<AnswerRow> answers, List<PointSpec> specs) {
        boolean titleAnswered = answers.stream().anyMatch(row -> !"empty".equals(row.verdict()));
        if (!titleAnswered) {
            return new GradeResult(null, "题面未作答，无法评级");
        }
        List<AnswerRow> judged = answers.stream()
                .filter(row -> !"empty".equals(row.verdict()))
                .toList();
        long wrongCount = judged.stream().filter(row -> "wrong".equals(row.verdict())).count();
        if (wrongCount > 0) {
            return new GradeResult(Grade.FAIL, "答错硬否决（" + wrongCount + " 问存在概念性错误）：面试里说反概念即不通过");
        }
        Set<String> passIds = new HashSet<>();
        Set<String> goodIds = new HashSet<>();
        Set<String> excellentIds = new HashSet<>();
        for (PointSpec spec : specs) {
            switch (spec.level() == null ? "" : spec.level()) {
                case "pass" -> passIds.add(spec.id());
                case "good" -> goodIds.add(spec.id());
                case "excellent" -> excellentIds.add(spec.id());
                default -> {
                    // 未知档位要点不参与公式（数据异常由内容侧巡检兜底）
                }
            }
        }
        if (passIds.isEmpty()) {
            return new GradeResult(null, "该知识点无及格级（pass）评分要点，无法评级——请在内容侧补齐评分要点标定");
        }
        Set<String> hitIds = new HashSet<>();
        for (AnswerRow row : judged) {
            for (PointHit hit : row.points() == null ? List.<PointHit>of() : row.points()) {
                if (hit.hit()) {
                    hitIds.add(hit.id());
                }
            }
        }
        boolean passAll = hitIds.containsAll(passIds);
        if (!passAll) {
            return new GradeResult(Grade.FAIL, "及格级要点未全部命中（" + hitIds.stream().filter(passIds::contains).count()
                    + "/" + passIds.size() + "）");
        }
        boolean goodAll = goodIds.isEmpty() || hitIds.containsAll(goodIds);
        boolean excellentAny = excellentIds.stream().anyMatch(hitIds::contains);
        if (!goodAll) {
            return new GradeResult(Grade.PASS, "及格级要点全命中，良好级未全命中");
        }
        if (excellentAny) {
            return new GradeResult(Grade.EXCELLENT, "及格/良好级要点全命中，且有优秀级要点命中");
        }
        return new GradeResult(Grade.GOOD, "及格/良好级要点全命中（无优秀级命中）");
    }
}
