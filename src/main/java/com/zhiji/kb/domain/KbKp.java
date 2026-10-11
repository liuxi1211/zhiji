package com.zhiji.kb.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 知识点（Knowledge Point，核心模块定义 5.2/6.5）：面试题线的标准内容实体，
 * 学习、判定、模拟考的主线实体。结构化列（变体/追问链/评分要点/样例/职级规则/前置依赖）
 * 以 JSON 字符串承载——SQLite 无 JSON 类型，读写由 service 层序列化，不按子字段查询。
 */
@TableName("kb_kp")
public class KbKp {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String domainCode;

    private Long topicId;

    /** 题面 */
    private String title;

    /** 可读摘要（目录与同位管理始终有可读摘要，概述是入库门槛的落点） */
    private String summary;

    /** 叙述框架/正文（Markdown 快照） */
    private String frameworkMd;

    /** 问法变体 [{"text"}] */
    private String variants;

    /** 追问链 [{"id","layer","direction","content","status"}]；id 经分配永不复用永不修改（红线 2） */
    private String followups;

    /** 分级评分要点 [{"id","level","content"}]，判定引擎的对齐尺子 */
    private String scoringPoints;

    /** 三档回答样例 [{"level","content"}]（及格/良好/优秀） */
    private String answerSamples;

    /** 内容难度四档（junior/mid/senior/expert），与职级要求正交（红线 6） */
    private String difficulty;

    /** 职级要求与停止点 [{"level","required","stop_layer"}] */
    private String levelRules;

    /** 前置依赖 [kpId]（学习维度） */
    private String prerequisites;

    /** 频率信号·人工标定 */
    private Integer freqManual;

    /** 频率信号·面经回流（复盘模块接入，一期恒 0） */
    private Integer freqSignal;

    /** 频率信号·覆盖度（合并通过时由覆盖源数转入） */
    private Integer freqCoverage;

    /** draft / pending / available / deprecated / merged（kb_status 常量组） */
    private String status;

    /** 合并下线指向的产物知识点（恢复时须写回 NULL：update 策略 ALWAYS，null 也参与 SET） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long mergedIntoId;

    /** 非空即合并产物：源 id JSON 数组，审核通过时据此把源标 merged */
    private String mergeSources;

    /** 复核到期时间（放行时设置，到期回审核队列复检） */
    private String reviewDueAt;

    private Long createdBy;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDomainCode() {
        return domainCode;
    }

    public void setDomainCode(String domainCode) {
        this.domainCode = domainCode;
    }

    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getFrameworkMd() {
        return frameworkMd;
    }

    public void setFrameworkMd(String frameworkMd) {
        this.frameworkMd = frameworkMd;
    }

    public String getVariants() {
        return variants;
    }

    public void setVariants(String variants) {
        this.variants = variants;
    }

    public String getFollowups() {
        return followups;
    }

    public void setFollowups(String followups) {
        this.followups = followups;
    }

    public String getScoringPoints() {
        return scoringPoints;
    }

    public void setScoringPoints(String scoringPoints) {
        this.scoringPoints = scoringPoints;
    }

    public String getAnswerSamples() {
        return answerSamples;
    }

    public void setAnswerSamples(String answerSamples) {
        this.answerSamples = answerSamples;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getLevelRules() {
        return levelRules;
    }

    public void setLevelRules(String levelRules) {
        this.levelRules = levelRules;
    }

    public String getPrerequisites() {
        return prerequisites;
    }

    public void setPrerequisites(String prerequisites) {
        this.prerequisites = prerequisites;
    }

    public Integer getFreqManual() {
        return freqManual;
    }

    public void setFreqManual(Integer freqManual) {
        this.freqManual = freqManual;
    }

    public Integer getFreqSignal() {
        return freqSignal;
    }

    public void setFreqSignal(Integer freqSignal) {
        this.freqSignal = freqSignal;
    }

    public Integer getFreqCoverage() {
        return freqCoverage;
    }

    public void setFreqCoverage(Integer freqCoverage) {
        this.freqCoverage = freqCoverage;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getMergedIntoId() {
        return mergedIntoId;
    }

    public void setMergedIntoId(Long mergedIntoId) {
        this.mergedIntoId = mergedIntoId;
    }

    public String getMergeSources() {
        return mergeSources;
    }

    public void setMergeSources(String mergeSources) {
        this.mergeSources = mergeSources;
    }

    public String getReviewDueAt() {
        return reviewDueAt;
    }

    public void setReviewDueAt(String reviewDueAt) {
        this.reviewDueAt = reviewDueAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
