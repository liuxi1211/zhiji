package com.zhiji.kb.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 资料讲解（Doc，核心模块定义 5.2/6.5）：文章线的标准内容实体。
 * 无评分要求、无追问链，挂载到知识点即发布（挂载动作即人工发布决策）；
 * 题目"看不懂"时的展开层，不直接参与学习主线。
 */
@TableName("kb_doc")
public class KbDoc {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String domainCode;

    private Long topicId;

    private String title;

    private String summary;

    /** 讲解正文（Markdown 快照） */
    private String contentMd;

    private String difficulty;

    /** 来源溯源（素材侧 URL 自动带入，版权追溯；仅此一处留存，不进知识点） */
    private String sourceUrl;

    /** 挂载的知识点（挂载动作即人工发布决策） */
    private Long kpId;

    /** draft / pending / available / deprecated / merged（kb_status 常量组） */
    private String status;

    /** 合并下线指向的产物资料（恢复时须写回 NULL：update 策略 ALWAYS，null 也参与 SET） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long mergedIntoId;

    private String mergeSources;

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

    public String getContentMd() {
        return contentMd;
    }

    public void setContentMd(String contentMd) {
        this.contentMd = contentMd;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
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
