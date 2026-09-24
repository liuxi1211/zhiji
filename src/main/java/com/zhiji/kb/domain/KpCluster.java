package com.zhiji.kb.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kp_cluster")
public class KpCluster {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long domainId;

    private String name;

    private String description;

    private String zeroShotMd;

    private String referenceSolutionMd;

    /** draft / reviewed / active / retired（CHECK 约束），状态机推进规则在 service 层用 ContentStatus 解析 */
    private String status;

    private Long mergedInto;

    private String lastReviewedAt;

    private String reviewDueAt;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDomainId() {
        return domainId;
    }

    public void setDomainId(Long domainId) {
        this.domainId = domainId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getZeroShotMd() {
        return zeroShotMd;
    }

    public void setZeroShotMd(String zeroShotMd) {
        this.zeroShotMd = zeroShotMd;
    }

    public String getReferenceSolutionMd() {
        return referenceSolutionMd;
    }

    public void setReferenceSolutionMd(String referenceSolutionMd) {
        this.referenceSolutionMd = referenceSolutionMd;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getMergedInto() {
        return mergedInto;
    }

    public void setMergedInto(Long mergedInto) {
        this.mergedInto = mergedInto;
    }

    public String getLastReviewedAt() {
        return lastReviewedAt;
    }

    public void setLastReviewedAt(String lastReviewedAt) {
        this.lastReviewedAt = lastReviewedAt;
    }

    public String getReviewDueAt() {
        return reviewDueAt;
    }

    public void setReviewDueAt(String reviewDueAt) {
        this.reviewDueAt = reviewDueAt;
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
