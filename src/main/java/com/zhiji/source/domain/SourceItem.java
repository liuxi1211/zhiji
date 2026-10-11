package com.zhiji.source.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 素材（Source Item，核心模块定义 5.1）：素材库的基本单位，一段待加工内容及其加工记录。
 * 拆分产出的子素材 parent_id 指向母素材，其余环节对素材与子素材一视同仁。
 */
@TableName("src_item")
public class SourceItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 拆分产出的子素材指向母素材；null 即母素材 */
    private Long parentId;

    private String title;

    /** Markdown 正文；图片引用形如 asset:source/{itemId}/{file}（落盘即真相的路径约定） */
    private String contentMd;

    /** 素材概述（百字内）：齐备是提交进入标准库的前置条件（5.1 边界「概述是入库门槛」） */
    private String summary;

    /** 内容难度四档（junior/mid/senior/expert，常量组 difficulty）；向导内 AI 建议、人工拍板 */
    private String difficulty;

    /** 领域代码（常量组 domain，人工点选；AI 分类只在所选领域内进行） */
    private String domainCode;

    /** 主题位置（AI 建议或人工填写，入库前的工作位信息；标准库实体化时才真正生效） */
    private String topicName;

    /** manual / import / url / inferred（source_type 常量组） */
    private String sourceType;

    /** 原始出处：URL 抓取自动带入或人工登记，服务版权追溯（不做渠道分类管理） */
    private String sourceUrl;

    /** pending / submitted / discarded（src_status 常量组） */
    private String status;

    /** 提交入库时间：素材入库后保留不删，这是溯源的起点 */
    private String submittedAt;

    private Long createdBy;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContentMd() {
        return contentMd;
    }

    public void setContentMd(String contentMd) {
        this.contentMd = contentMd;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getDomainCode() {
        return domainCode;
    }

    public void setDomainCode(String domainCode) {
        this.domainCode = domainCode;
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(String submittedAt) {
        this.submittedAt = submittedAt;
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
