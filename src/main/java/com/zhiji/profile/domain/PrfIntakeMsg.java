package com.zhiji.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 建档对话消息（Intake Brainstorm，PRD v2 4.4）：role=user 存用户反馈原文，
 * role=assistant 存教练回复——text 为给用户看的一句话说明，claims_json 为该轮技能条
 * 版本 JSON（可空：闲聊/提问轮没有新版本）。对话即事实采集的主通道，轮次上限 5。
 */
@TableName("prf_intake_msg")
public class PrfIntakeMsg {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long profileId;

    /** user / assistant */
    private String role;

    /** user = 反馈原文；assistant = 给用户看的一句话说明 */
    private String text;

    /** assistant 专属：该轮技能条版本 JSON [{"title":"…","content":"…"}]，闲聊轮为 null */
    private String claimsJson;

    private String createdAt;

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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getClaimsJson() {
        return claimsJson;
    }

    public void setClaimsJson(String claimsJson) {
        this.claimsJson = claimsJson;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
