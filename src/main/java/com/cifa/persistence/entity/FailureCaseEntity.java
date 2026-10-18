package com.cifa.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * Manually promoted, human-confirmed diagnosis (plan §八). Nothing in the
 * normal diagnosis or eval flow may ever insert here — P1-9's test proves it.
 */
@TableName("failure_case")
public class FailureCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sourceTaskId;

    private String failureFingerprint;

    private String category;

    private String confirmedDiagnosis;

    private String confirmedAction;

    private String confirmedBy;

    private OffsetDateTime confirmedAt;

    private String reviewerFeedback;

    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceTaskId() {
        return sourceTaskId;
    }

    public void setSourceTaskId(String sourceTaskId) {
        this.sourceTaskId = sourceTaskId;
    }

    public String getFailureFingerprint() {
        return failureFingerprint;
    }

    public void setFailureFingerprint(String failureFingerprint) {
        this.failureFingerprint = failureFingerprint;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getConfirmedDiagnosis() {
        return confirmedDiagnosis;
    }

    public void setConfirmedDiagnosis(String confirmedDiagnosis) {
        this.confirmedDiagnosis = confirmedDiagnosis;
    }

    public String getConfirmedAction() {
        return confirmedAction;
    }

    public void setConfirmedAction(String confirmedAction) {
        this.confirmedAction = confirmedAction;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(OffsetDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getReviewerFeedback() {
        return reviewerFeedback;
    }

    public void setReviewerFeedback(String reviewerFeedback) {
        this.reviewerFeedback = reviewerFeedback;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
