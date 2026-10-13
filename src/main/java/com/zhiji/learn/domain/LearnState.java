package com.zhiji.learn.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 知识点学习状态（Learning State，核心模块定义 4.2/6.5）：每档案 × 每知识点一行，
 * 学习与复习共用的数据源（T11 学习/练习落账，T12 今日复习消费）。
 *
 * 五态状态机：learning（进行中）→ quick|pass|weak；weak → quick|pass；
 * abandoned（已放弃）→ learning（恢复）；任意 → abandoned。
 * quick/pass/weak 三态均算已推进（单元完成度与复习池入池口径）；
 * progressed_at 为 T12 间隔重复的时间起点。
 */
@TableName("learn_state")
public class LearnState {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long profileId;

    private Long kpId;

    /** learning / quick / pass / weak / abandoned（learn_status 常量组） */
    private String status;

    /** 最近一次练习判定会话（judge_session.id）；速览/放弃为 null */
    private Long sessionId;

    /** 放弃原因（可选填，放弃清单展示） */
    private String abandonReason;

    /** 达成 quick/pass/weak 的时间（间隔重复起点）；learning/abandoned 为 null */
    private String progressedAt;

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

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getAbandonReason() {
        return abandonReason;
    }

    public void setAbandonReason(String abandonReason) {
        this.abandonReason = abandonReason;
    }

    public String getProgressedAt() {
        return progressedAt;
    }

    public void setProgressedAt(String progressedAt) {
        this.progressedAt = progressedAt;
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
