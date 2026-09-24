package com.zhiji.kb.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kp_prereq")
public class KpPrereq {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kpId;

    private Long prereqKpId;

    public KpPrereq() {
    }

    public KpPrereq(Long kpId, Long prereqKpId) {
        this.kpId = kpId;
        this.prereqKpId = prereqKpId;
    }

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

    public Long getPrereqKpId() {
        return prereqKpId;
    }

    public void setPrereqKpId(Long prereqKpId) {
        this.prereqKpId = prereqKpId;
    }
}
