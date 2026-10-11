package com.zhiji.judge.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 判定作答行（Judge Answer，核心模块定义 6.1）：一次自由作答的对齐结论，只插不改。
 * 快照即真相——question_text 与 points_result 落作答时的内容，改版后历史档位可重算。
 */
@TableName("judge_answer")
public class JudgeAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** null=题面作答；否则追问层 id（永久身份，红线 2） */
    private String followupId;

    /** 作答时的问题快照（题面/追问层内容） */
    private String questionText;

    /** 0=题面，追问层为层号 */
    private Integer layer;

    private String answerText;

    /** correct/missing/wrong/empty；null=判定失败待降级自评 */
    private String verdict;

    /** llm=引擎判定 / self=降级自评 / code=空答短路 */
    private String source;

    /** 评分要点全量快照 [{"id","level","content","hit","quote"}] 含未命中项 */
    private String pointsResult;

    private String wrongNote;

    /** 判定失败留痕（解锁自评的前置证据） */
    private String errorNote;

    private String createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getFollowupId() {
        return followupId;
    }

    public void setFollowupId(String followupId) {
        this.followupId = followupId;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public Integer getLayer() {
        return layer;
    }

    public void setLayer(Integer layer) {
        this.layer = layer;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPointsResult() {
        return pointsResult;
    }

    public void setPointsResult(String pointsResult) {
        this.pointsResult = pointsResult;
    }

    public String getWrongNote() {
        return wrongNote;
    }

    public void setWrongNote(String wrongNote) {
        this.wrongNote = wrongNote;
    }

    public String getErrorNote() {
        return errorNote;
    }

    public void setErrorNote(String errorNote) {
        this.errorNote = errorNote;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
