package com.zhiji.review.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 复习结果流水（核心模块定义 4.3，T12）：今日复习/随机抽取的每次自评一条。
 * 间隔重复连对 streak 与连错检测从本表推导；虚报/失败证据同时累计在
 * learn_state.fail_count（快照列）供复盘降级建议（T13）直读。
 */
@TableName("review_log")
public class ReviewLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long profileId;

    private Long kpId;

    /** 1=自评会 0=自评不会 */
    private Integer known;

    /** today=今日清单 / draw=随机抽取 */
    private String mode;

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

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
    }

    public Integer getKnown() {
        return known;
    }

    public void setKnown(Integer known) {
        this.known = known;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
