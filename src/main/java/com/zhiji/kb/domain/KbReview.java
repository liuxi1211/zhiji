package com.zhiji.kb.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 审核记录（Review，核心模块定义 5.2）：进门质量门的留痕流水，只插不改、可回溯。
 * 动作式流水：submit/resubmit/recheck 入队，pass/reject/discard 出队，每次动作一行。
 */
@TableName("kb_review")
public class KbReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** kp / doc */
    private String contentType;

    private Long contentId;

    /** submit / resubmit / recheck / pass / reject / discard */
    private String action;

    /** wizard / direct / merge / recheck（入队来源） */
    private String source;

    /** 四项强制校验 {"correctness","judgeable","infringement","quality","notes"}，出队动作填写 */
    private String checks;

    private String comment;

    private Long operatorId;

    private String createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getContentId() {
        return contentId;
    }

    public void setContentId(Long contentId) {
        this.contentId = contentId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getChecks() {
        return checks;
    }

    public void setChecks(String checks) {
        this.checks = checks;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
