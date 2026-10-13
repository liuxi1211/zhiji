package com.zhiji.judge.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 判定会话（Judge Session，核心模块定义 6.1/6.5）：一个知识点 × 目标职级的连续作答。
 * 停止点在创建时快照自知识点职级规则——会话期内内容改版不影响本会话的判定范围。
 */
@TableName("judge_session")
public class JudgeSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kpId;

    /** 目标职级（junior/mid/senior/expert），全产品深度标尺 */
    private String level;

    /** 判定范围界：层号超过它的追问层可作答但不计入档位；null=未标定取最大层 */
    private Integer stopLayer;

    /** open / closed */
    private String status;

    /** 四档总评 excellent/good/pass/fail（公式计算）；null=未关闭或无法评级 */
    private String grade;

    /** 无法评级原因或公式说明 */
    private String gradeNote;

    /** 会话中出现降级自评 */
    private Integer degraded;

    private String closedAt;

    /** 归属侧别：staff=试判台（B 端员工）/ customer=练习模式（C 端客户，created_by 存档案 id） */
    private String ownerType;

    private Long createdBy;

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

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public Integer getStopLayer() {
        return stopLayer;
    }

    public void setStopLayer(Integer stopLayer) {
        this.stopLayer = stopLayer;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getGradeNote() {
        return gradeNote;
    }

    public void setGradeNote(String gradeNote) {
        this.gradeNote = gradeNote;
    }

    public Integer getDegraded() {
        return degraded;
    }

    public void setDegraded(Integer degraded) {
        this.degraded = degraded;
    }

    public String getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(String closedAt) {
        this.closedAt = closedAt;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public void setOwnerType(String ownerType) {
        this.ownerType = ownerType;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
