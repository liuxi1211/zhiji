package com.zhiji.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 技能条（Skill Claim，核心模块定义 4.1/6.5）：小标题 + 一段简历措辞 + 其锚定的知识点集合，
 * 简历主张的载体。锚点必须真实存在于题库且上线（代码校验，AI 不编造考点）。
 * 对话流（PRD v2）：技能条由建档对话起草采纳，锚点在定稿后由映射补齐（anchor_status）。
 */
@TableName("prf_claim")
public class PrfClaim {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long profileId;

    /** 小标题（如"存储与分库分表"） */
    private String title;

    /** 简历措辞（可含未处理占位【…】，定稿前必须清零） */
    private String wording;

    /** 锚定知识点 id JSON 数组 [kpId]（String 列，service 层序列化，先例 KbKp） */
    private String anchorIds;

    /** unmapped 未映射 / mapped 已锚定 / pending 低置信待确认 / zero 零命中 */
    private String anchorStatus;

    /** ai（对话起草采纳）/ manual（手动添加） */
    private String source;

    /** unconfirmed / confirmed（claim_status 常量组） */
    private String status;

    private Integer sortOrder;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProfileId() {
        return profileId;
    }

    public void setProfileId(Long profileId) {
        this.profileId = profileId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getWording() {
        return wording;
    }

    public void setWording(String wording) {
        this.wording = wording;
    }

    public String getAnchorIds() {
        return anchorIds;
    }

    public void setAnchorIds(String anchorIds) {
        this.anchorIds = anchorIds;
    }

    public String getAnchorStatus() {
        return anchorStatus;
    }

    public void setAnchorStatus(String anchorStatus) {
        this.anchorStatus = anchorStatus;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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
