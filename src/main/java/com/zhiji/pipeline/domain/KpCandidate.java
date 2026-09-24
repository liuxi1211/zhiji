package com.zhiji.pipeline.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("kp_candidate")
public class KpCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private String batchId;

    private String title;

    private String answerEvidence;

    private String sectionPath;

    /** question_bank / reading / interview（片段级，可人工修正） */
    private String form;

    /** pending / matched / converting / converted / merged / discarded（CHECK 约束，CandidateStatus 解析） */
    private String status;

    /** 同题归一指向（kp_canonical 随 T7 落库） */
    private Long canonicalId;

    /** high 结构命中 / low LLM 兜底 */
    private String confidence;

    private String note;

    private String createdAt;

    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAnswerEvidence() {
        return answerEvidence;
    }

    public void setAnswerEvidence(String answerEvidence) {
        this.answerEvidence = answerEvidence;
    }

    public String getSectionPath() {
        return sectionPath;
    }

    public void setSectionPath(String sectionPath) {
        this.sectionPath = sectionPath;
    }

    public String getForm() {
        return form;
    }

    public void setForm(String form) {
        this.form = form;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCanonicalId() {
        return canonicalId;
    }

    public void setCanonicalId(Long canonicalId) {
        this.canonicalId = canonicalId;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
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

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
