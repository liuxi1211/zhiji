package com.zhiji.profile.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 简历档案（Profile，核心模块定义 4.1/6.5）：个人备考上下文的顶层归属，每用户最多 3 份，档案间进度隔离。
 * 备考计划（目标岗位与 JD、目标职级、备考周期、每日可投入时间）承载在档案上；
 * 目标职级是全产品的深度标尺。可空列以 update 策略 ALWAYS 支持"清空为 null"的整表替换语义。
 */
@TableName("prf_profile")
public class PrfProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    /** 档案名（如"Java 后端"） */
    private String name;

    /** 简历全文（一期粘贴文本，上传解析后置） */
    private String resumeText;

    /** 工作年限（补齐表单，职级缺省推测依据） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer years;

    /** 目标岗位 */
    private String position;

    /** 目标岗位 JD */
    private String jdText;

    /** 技术栈（自由文本） */
    private String techStack;

    /** 目标职级（Target Level 五档；null=未设定，定稿前必填） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String targetLevel;

    /** 备考周期（周） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer planWeeks;

    /** 每日可投入时间（分钟） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer dailyMinutes;

    /** draft / confirmed / finalized（profile_status 常量组） */
    private String status;

    /** 当前档案标记（部分唯一索引保证每客户至多一个） */
    private Integer isActive;

    /** 定稿时间（撤销定稿时清空） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String finalizedAt;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResumeText() {
        return resumeText;
    }

    public void setResumeText(String resumeText) {
        this.resumeText = resumeText;
    }

    public Integer getYears() {
        return years;
    }

    public void setYears(Integer years) {
        this.years = years;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getJdText() {
        return jdText;
    }

    public void setJdText(String jdText) {
        this.jdText = jdText;
    }

    public String getTechStack() {
        return techStack;
    }

    public void setTechStack(String techStack) {
        this.techStack = techStack;
    }

    public String getTargetLevel() {
        return targetLevel;
    }

    public void setTargetLevel(String targetLevel) {
        this.targetLevel = targetLevel;
    }

    public Integer getPlanWeeks() {
        return planWeeks;
    }

    public void setPlanWeeks(Integer planWeeks) {
        this.planWeeks = planWeeks;
    }

    public Integer getDailyMinutes() {
        return dailyMinutes;
    }

    public void setDailyMinutes(Integer dailyMinutes) {
        this.dailyMinutes = dailyMinutes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getIsActive() {
        return isActive;
    }

    public void setIsActive(Integer isActive) {
        this.isActive = isActive;
    }

    public String getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(String finalizedAt) {
        this.finalizedAt = finalizedAt;
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
