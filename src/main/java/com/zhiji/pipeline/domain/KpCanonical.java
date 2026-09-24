package com.zhiji.pipeline.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kp_canonical")
public class KpCanonical {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** 归属语料簇（draft 态 kp_cluster，逻辑引用无 FK） */
    private Long clusterId;

    /** 覆盖源数：去重素材文档数 */
    private Integer coverageCount;

    /** pending / confirmed / built（CHECK 约束，CanonicalStatus 解析） */
    private String status;

    /** 已建设知识点指向（演化锚点 D21，逻辑引用 kp_main 无 FK） */
    private Long builtKpId;

    /** structural 结构强命中 / exact 极高相似 / llm LLM 精判 */
    private String judgeBasis;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getClusterId() {
        return clusterId;
    }

    public void setClusterId(Long clusterId) {
        this.clusterId = clusterId;
    }

    public Integer getCoverageCount() {
        return coverageCount;
    }

    public void setCoverageCount(Integer coverageCount) {
        this.coverageCount = coverageCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getBuiltKpId() {
        return builtKpId;
    }

    public void setBuiltKpId(Long builtKpId) {
        this.builtKpId = builtKpId;
    }

    public String getJudgeBasis() {
        return judgeBasis;
    }

    public void setJudgeBasis(String judgeBasis) {
        this.judgeBasis = judgeBasis;
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
