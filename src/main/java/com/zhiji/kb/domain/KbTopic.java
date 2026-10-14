package com.zhiji.kb.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 主题（Topic，核心模块定义 5.2/6.5）：目录树第二层，领域内的分组节点。
 * 承载名字与位置、不承载内容；名字前端可见、可人工改名；T4 起主题清单以本表为准，
 * DomainTopic 常量组退化为种子数据（KbBootstrap 幂等种子）。
 */
@TableName("kb_topic")
public class KbTopic {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 领域代码（常量组 domain，人工维护边界） */
    private String domainCode;

    private String name;

    /** 覆盖范围描述：目录/下拉的人读提示，同时注入 AI 定位提示词（可空） */
    private String description;

    /** active / merged（合并后不再直接承接内容，目录树隐藏） */
    private String status;

    /** 主题合并指向的目标主题 */
    private Long mergedIntoId;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDomainCode() {
        return domainCode;
    }

    public void setDomainCode(String domainCode) {
        this.domainCode = domainCode;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getMergedIntoId() {
        return mergedIntoId;
    }

    public void setMergedIntoId(Long mergedIntoId) {
        this.mergedIntoId = mergedIntoId;
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
