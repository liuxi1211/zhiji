package com.zhiji.kb.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kp_freq_signal")
public class KpFreqSignal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kpId;

    /** manual / interview / coverage（CHECK 约束） */
    private String source;

    private String signalLevel;

    private Integer signalCount;

    private String note;

    private String createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKpId() {
        return kpId;
    }

    public void setKpId(Long kpId) {
        this.kpId = kpId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSignalLevel() {
        return signalLevel;
    }

    public void setSignalLevel(String signalLevel) {
        this.signalLevel = signalLevel;
    }

    public Integer getSignalCount() {
        return signalCount;
    }

    public void setSignalCount(Integer signalCount) {
        this.signalCount = signalCount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
