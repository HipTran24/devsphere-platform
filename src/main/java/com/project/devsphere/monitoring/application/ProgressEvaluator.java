package com.project.devsphere.monitoring.application;

import com.project.devsphere.monitoring.api.dto.CodeEvidenceDto;
import com.project.devsphere.monitoring.api.dto.CriterionEvaluationDto;
import com.project.devsphere.monitoring.api.dto.ProgressEvaluationResponse;
import com.project.devsphere.workitem.domain.AcceptanceCriterion;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ProgressEvaluator {

    private static final BigDecimal DISCREPANCY_THRESHOLD = new BigDecimal("25.00");

    public record EvaluationInput(
            UUID workItemId,
            String itemKey,
            String workKind,
            String status,
            BigDecimal claimedProgress,
            List<AcceptanceCriterion> criteria,
            CodeEvidenceDto codeEvidence
    ) {}

    public ProgressEvaluationResponse evaluate(EvaluationInput input) {
        if (input == null) {
            throw MonitoringException.validationFailed("EvaluationInput must not be null");
        }

        UUID workItemId = input.workItemId();
        String itemKey = input.itemKey();
        String workKind = input.workKind() != null ? input.workKind().toUpperCase() : "NON_CODE";
        String status = input.status() != null ? input.status().toUpperCase() : "TO_DO";
        BigDecimal claimedProgress = input.claimedProgress() != null ? input.claimedProgress() : BigDecimal.ZERO;
        List<AcceptanceCriterion> criteriaList = input.criteria() != null ? input.criteria() : List.of();
        CodeEvidenceDto codeEvidence = input.codeEvidence();

        List<CriterionEvaluationDto> criterionDtos = new ArrayList<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal verifiedWeight = BigDecimal.ZERO;
        int verifiedCount = 0;

        for (AcceptanceCriterion c : criteriaList) {
            BigDecimal w = c.getWeight() != null && c.getWeight().compareTo(BigDecimal.ZERO) > 0
                    ? c.getWeight()
                    : BigDecimal.ONE;

            totalWeight = totalWeight.add(w);

            boolean isVerified = c.getStatus() == AcceptanceCriterion.Status.VERIFIED;
            if (isVerified) {
                verifiedWeight = verifiedWeight.add(w);
                verifiedCount++;
            }

            criterionDtos.add(new CriterionEvaluationDto(
                    c.getId(),
                    c.getPosition(),
                    c.getDescription(),
                    c.getEvidenceKind() != null ? c.getEvidenceKind().name() : null,
                    w,
                    c.getStatus() != null ? c.getStatus().name() : AcceptanceCriterion.Status.NOT_EVALUATED.name(),
                    c.getEvidence() != null ? c.getEvidence() : List.of(),
                    c.getVerifiedBy(),
                    c.getVerifiedAt()
            ));
        }

        BigDecimal verifiedProgress = null;
        String evalStatus;
        boolean discrepancyDetected = false;

        if (totalWeight.compareTo(BigDecimal.ZERO) == 0 || criteriaList.isEmpty()) {
            // No criteria or total weight is zero: cannot establish verified ratio
            evalStatus = "INSUFFICIENT_EVIDENCE";
        } else {
            verifiedProgress = verifiedWeight
                    .multiply(new BigDecimal("100"))
                    .divide(totalWeight, 2, RoundingMode.HALF_UP);

            // Check discrepancy: claimedProgress - verifiedProgress >= 25%
            BigDecimal delta = claimedProgress.subtract(verifiedProgress);
            if (delta.compareTo(DISCREPANCY_THRESHOLD) >= 0) {
                discrepancyDetected = true;
                evalStatus = "DISCREPANCY_DETECTED";
            } else if (verifiedProgress.compareTo(new BigDecimal("100.00")) == 0) {
                evalStatus = "VERIFIED";
            } else {
                evalStatus = "IN_PROGRESS";
            }
        }

        // Additional evidence validation for CODE tasks
        if ("CODE".equalsIgnoreCase(workKind) && codeEvidence != null) {
            boolean codeEvidencePassed = codeEvidence.prLinked()
                    && codeEvidence.ciSuccess()
                    && codeEvidence.codeReviewPass()
                    && codeEvidence.independentReviewer();

            if (!codeEvidencePassed && "VERIFIED".equalsIgnoreCase(evalStatus)) {
                evalStatus = "EVIDENCE_INCOMPLETE";
            }
        }

        return new ProgressEvaluationResponse(
                workItemId,
                itemKey,
                workKind,
                status,
                claimedProgress,
                verifiedProgress,
                evalStatus,
                totalWeight,
                verifiedWeight,
                criteriaList.size(),
                verifiedCount,
                discrepancyDetected,
                codeEvidence,
                criterionDtos,
                Instant.now()
        );
    }
}
