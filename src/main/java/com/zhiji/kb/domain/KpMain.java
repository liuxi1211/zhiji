package com.zhiji.kb.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kp_main")
public class KpMain {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clusterId;

    /** default / design_chain / narrative（CHECK 约束） */
    private String subType;

    private String title;

    /** JSON 文本（SQLite 无 JSON 类型，存 TEXT，见 CLAUDE.md 陷阱 E） */
    private String variants;

    private String narrativeFramework;

    /** JSON 文本：追问层数组，layer_key 是稳定身份 */
    private String followups;

    /** JSON 文本：评分要点，分是公式算的，LLM 从不碰分数 */
    private String rubric;

    /** JSON 文本 */
    private String samples;

    /** JSON 文本 */
    private String levelReq;

    private String baselineLevels;

    /** 1~5（CHECK 约束） */
    private Integer difficulty;

    /** draft / reviewed / active / retired（CHECK 约束） */
    private String status;

    private Long mergedInto;

    private String lastReviewedAt;

    private String reviewDueAt;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getClusterId() {
        return clusterId;
    }

    public void setClusterId(Long clusterId) {
        this.clusterId = clusterId;
    }

    public String getSubType() {
        return subType;
    }

    public void setSubType(String subType) {
        this.subType = subType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVariants() {
        return variants;
    }

    public void setVariants(String variants) {
        this.variants = variants;
    }

    public String getNarrativeFramework() {
        return narrativeFramework;
    }

    public void setNarrativeFramework(String narrativeFramework) {
        this.narrativeFramework = narrativeFramework;
    }

    public String getFollowups() {
        return followups;
    }

    public void setFollowups(String followups) {
        this.followups = followups;
    }

    public String getRubric() {
        return rubric;
    }

    public void setRubric(String rubric) {
        this.rubric = rubric;
    }

    public String getSamples() {
        return samples;
    }

    public void setSamples(String samples) {
        this.samples = samples;
    }

    public String getLevelReq() {
        return levelReq;
    }

    public void setLevelReq(String levelReq) {
        this.levelReq = levelReq;
    }

    public String getBaselineLevels() {
        return baselineLevels;
    }

    public void setBaselineLevels(String baselineLevels) {
        this.baselineLevels = baselineLevels;
    }

    public Integer getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Integer difficulty) {
        this.difficulty = difficulty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getMergedInto() {
        return mergedInto;
    }

    public void setMergedInto(Long mergedInto) {
        this.mergedInto = mergedInto;
    }

    public String getLastReviewedAt() {
        return lastReviewedAt;
    }

    public void setLastReviewedAt(String lastReviewedAt) {
        this.lastReviewedAt = lastReviewedAt;
    }

    public String getReviewDueAt() {
        return reviewDueAt;
    }

    public void setReviewDueAt(String reviewDueAt) {
        this.reviewDueAt = reviewDueAt;
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
