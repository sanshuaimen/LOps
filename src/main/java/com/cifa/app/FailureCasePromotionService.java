package com.cifa.app;

import com.cifa.persistence.entity.FailureCaseEntity;
import com.cifa.persistence.mapper.FailureCaseMapper;
import org.springframework.stereotype.Component;

/**
 * The ONLY write path into failure_case (plan §八): an explicit, human-driven
 * promotion with mandatory auditor fields. Nothing in the diagnosis, eval, or
 * submission flow references this service — P1-9's test proves the isolation.
 */
@Component
public class FailureCasePromotionService {

    private final FailureCaseMapper failureCaseMapper;

    public FailureCasePromotionService(FailureCaseMapper failureCaseMapper) {
        this.failureCaseMapper = failureCaseMapper;
    }

    public long promote(String sourceTaskId, String failureFingerprint, String category,
                        String confirmedDiagnosis, String confirmedAction,
                        String confirmedBy, String reviewerFeedback) {
        if (confirmedBy == null || confirmedBy.isBlank()) {
            throw new IllegalArgumentException("human confirmer is required for promotion");
        }
        FailureCaseEntity row = new FailureCaseEntity();
        row.setSourceTaskId(sourceTaskId);
        row.setFailureFingerprint(failureFingerprint);
        row.setCategory(category);
        row.setConfirmedDiagnosis(confirmedDiagnosis);
        row.setConfirmedAction(confirmedAction == null ? "" : confirmedAction);
        row.setConfirmedBy(confirmedBy);
        row.setConfirmedAt(java.time.OffsetDateTime.now());
        row.setReviewerFeedback(reviewerFeedback == null ? "" : reviewerFeedback);
        failureCaseMapper.insert(row);
        return row.getId();
    }
}
