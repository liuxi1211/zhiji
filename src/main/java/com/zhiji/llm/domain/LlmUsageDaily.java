package com.zhiji.llm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * LLM 调用计数（入口 × 天）。profile_id = 0 表示无档案归属的调用（B 端人工触发 / 全站统计），
 * 不用 NULL 做「无档案」标记：SQLite UNIQUE 中 NULL 互不相等，多行并存会让 upsert 失效。
 */
@TableName("llm_usage_daily")
public class LlmUsageDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String statDate;

    private String purpose;

    private Long profileId;

    private Integer callCount;

    private Integer failCount;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatDate() {
        return statDate;
    }

    public void setStatDate(String statDate) {
        this.statDate = statDate;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public Long getProfileId() {
        return profileId;
    }

    public void setProfileId(Long profileId) {
        this.profileId = profileId;
    }

    public Integer getCallCount() {
        return callCount;
    }

    public void setCallCount(Integer callCount) {
        this.callCount = callCount;
    }

    public Integer getFailCount() {
        return failCount;
    }

    public void setFailCount(Integer failCount) {
        this.failCount = failCount;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
