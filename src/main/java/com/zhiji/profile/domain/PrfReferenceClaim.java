package com.zhiji.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 技能条参考库条目（Claim Reference Library，PRD v2 5.4）：从优秀简历脱敏收集的条目池，
 * 按岗位方向打标；对话起草时按方向过滤整池注入作风格锚——池中经历与数字是别人的，
 * 提示词铁律禁止搬用。
 */
@TableName("prf_reference_claim")
public class PrfReferenceClaim {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 岗位方向（'' = 通用） */
    private String direction;

    /** bar=单条（笔触示范） / section=整段样板（结构示范） */
    private String kind;

    /** 职级档位（section 用：junior/mid/senior/expert，与 targetLevel 同口径；bar 为空） */
    private String grade;

    /** 条目小标题（可空） */
    private String title;

    /** 条目正文（section 为多行整段） */
    private String content;

    private Integer sortOrder;

    private Integer enabled;

    private String createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
