package com.zhiji.judge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zhiji.judge.domain.Grade;
import com.zhiji.judge.service.GradeCalculator.AnswerRow;
import com.zhiji.judge.service.GradeCalculator.GradeResult;
import com.zhiji.judge.service.GradeCalculator.PointHit;
import com.zhiji.judge.service.GradeCalculator.PointSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 档位公式单测（D3 严格制���：硬否决、逐档门槛、无法评级路径、跨问累计（D5）。
 */
class GradeCalculatorTest {

    private static final List<PointSpec> SPECS = List.of(
            new PointSpec("sp1", "pass"),
            new PointSpec("sp2", "pass"),
            new PointSpec("g1", "good"),
            new PointSpec("e1", "excellent"));

    private static AnswerRow row(String verdict, PointHit... hits) {
        return new AnswerRow(verdict, List.of(hits));
    }

    @Test
    void failWhenWrong() {
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("sp1", "pass", true)),
                        row("wrong", new PointHit("sp1", "pass", true))),
                SPECS);
        assertThat(result.grade()).isEqualTo(Grade.FAIL);
        assertThat(result.note()).contains("硬否决");
    }

    @Test
    void ungradedWhenTitleEmpty() {
        GradeResult result = GradeCalculator.calculate(
                List.of(row("empty")),
                SPECS);
        assertThat(result.grade()).isNull();
        assertThat(result.note()).contains("题面未作答");
    }

    @Test
    void ungradedWhenNoPassPoints() {
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("g1", "good", true))),
                List.of(new PointSpec("g1", "good")));
        assertThat(result.grade()).isNull();
        assertThat(result.note()).contains("及格级");
    }

    @Test
    void failWhenPassNotAllHit() {
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("sp1", "pass", true))),
                SPECS);
        assertThat(result.grade()).isEqualTo(Grade.FAIL);
        assertThat(result.note()).contains("1/2");
    }

    @Test
    void passAcrossAnswersAccumulate() {
        // 跨问累计：sp1 在第一问命中、sp2 在追问命中，合并后及格
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("sp1", "pass", true), new PointHit("sp2", "pass", false)),
                        row("missing", new PointHit("sp2", "pass", true))),
                SPECS);
        assertThat(result.grade()).isEqualTo(Grade.PASS);
    }

    @Test
    void goodNeedsAllGoodPoints() {
        // g1 尚未命中 → 只到及格；good 全命中后才能良好（passAcrossAnswers 覆盖了后者）
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("sp1", "pass", true), new PointHit("sp2", "pass", true),
                        new PointHit("g1", "good", false))),
                SPECS);
        assertThat(result.grade()).isEqualTo(Grade.PASS);
    }

    @Test
    void goodWhenPassAndGoodAllHit() {
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("sp1", "pass", true), new PointHit("sp2", "pass", true),
                        new PointHit("g1", "good", true)),
                        row("correct", new PointHit("g1", "good", false))),
                SPECS);
        assertThat(result.grade()).isEqualTo(Grade.GOOD);
    }

    @Test
    void excellentNeedsOneExcellentHit() {
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("sp1", "pass", true), new PointHit("sp2", "pass", true),
                        new PointHit("g1", "good", true), new PointHit("e1", "excellent", true))),
                SPECS);
        assertThat(result.grade()).isEqualTo(Grade.EXCELLENT);
    }

    @Test
    void emptyRowsDoNotBlockGrade() {
        // 空答不参与档位：题面已答，追问全空，仍按命中分布评级
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("sp1", "pass", true), new PointHit("sp2", "pass", true),
                        new PointHit("g1", "good", true)),
                        row("empty")),
                SPECS);
        assertThat(result.grade()).isEqualTo(Grade.GOOD);
    }

    @Test
    void unknownPointLevelIgnored() {
        GradeResult result = GradeCalculator.calculate(
                List.of(row("correct", new PointHit("sp1", "pass", true), new PointHit("sp2", "pass", true),
                        new PointHit("g1", "good", true))),
                List.of(new PointSpec("sp1", "pass"), new PointSpec("sp2", "pass"),
                        new PointSpec("g1", "good"), new PointSpec("weird", "bogus")));
        assertThat(result.grade()).isEqualTo(Grade.GOOD);
    }
}
