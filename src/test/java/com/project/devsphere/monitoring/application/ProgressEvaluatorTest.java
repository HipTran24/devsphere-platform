package com.project.devsphere.monitoring.application;

import com.project.devsphere.monitoring.api.dto.CodeEvidenceDto;
import com.project.devsphere.monitoring.api.dto.ProgressEvaluationResponse;
import com.project.devsphere.workitem.domain.AcceptanceCriterion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressEvaluatorTest {

    private ProgressEvaluator evaluator;
    private final UUID workItemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        evaluator = new ProgressEvaluator();
    }

    private AcceptanceCriterion createCriterion(int pos, double weight, AcceptanceCriterion.Status status) {
        AcceptanceCriterion c = new AcceptanceCriterion();
        c.setId(UUID.randomUUID());
        c.setPosition(pos);
        c.setDescription("Criterion " + pos);
        c.setWeight(BigDecimal.valueOf(weight));
        c.setStatus(status);
        return c;
    }

    @Test
    @DisplayName("Empty criteria list yields null verified progress and INSUFFICIENT_EVIDENCE")
    void evaluate_emptyCriteria_returnsInsufficientEvidence() {
        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                "TASK-1",
                "NON_CODE",
                "TO_DO",
                BigDecimal.ZERO,
                List.of(),
                null
        );

        ProgressEvaluationResponse res = evaluator.evaluate(input);

        assertThat(res.verifiedProgress()).isNull();
        assertThat(res.evaluationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(res.totalCriteriaCount()).isEqualTo(0);
        assertThat(res.verifiedCriteriaCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Equal weights criteria (1, 1, 1) with 2 verified calculates 66.67%")
    void evaluate_equalWeights_calculatesExactRatio() {
        List<AcceptanceCriterion> criteria = List.of(
                createCriterion(1, 1.0, AcceptanceCriterion.Status.VERIFIED),
                createCriterion(2, 1.0, AcceptanceCriterion.Status.VERIFIED),
                createCriterion(3, 1.0, AcceptanceCriterion.Status.NOT_MET)
        );

        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                "TASK-2",
                "NON_CODE",
                "IN_PROGRESS",
                new BigDecimal("60.00"),
                criteria,
                null
        );

        ProgressEvaluationResponse res = evaluator.evaluate(input);

        assertThat(res.verifiedProgress()).isEqualByComparingTo(new BigDecimal("66.67"));
        assertThat(res.evaluationStatus()).isEqualTo("IN_PROGRESS");
        assertThat(res.discrepancyDetected()).isFalse();
        assertThat(res.totalCriteriaCount()).isEqualTo(3);
        assertThat(res.verifiedCriteriaCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Unequal weights criteria (2, 3, 5) with first two verified calculates 50.00%")
    void evaluate_unequalWeights_calculatesWeightedPercentage() {
        List<AcceptanceCriterion> criteria = List.of(
                createCriterion(1, 2.0, AcceptanceCriterion.Status.VERIFIED),
                createCriterion(2, 3.0, AcceptanceCriterion.Status.VERIFIED),
                createCriterion(3, 5.0, AcceptanceCriterion.Status.NOT_EVALUATED)
        );

        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                "TASK-3",
                "NON_CODE",
                "IN_PROGRESS",
                new BigDecimal("50.00"),
                criteria,
                null
        );

        ProgressEvaluationResponse res = evaluator.evaluate(input);

        assertThat(res.verifiedProgress()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(res.totalCriteriaWeight()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(res.verifiedCriteriaWeight()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    @DisplayName("All criteria verified yields 100.00% and VERIFIED status")
    void evaluate_allCriteriaVerified_statusIsVerified() {
        List<AcceptanceCriterion> criteria = List.of(
                createCriterion(1, 1.0, AcceptanceCriterion.Status.VERIFIED),
                createCriterion(2, 2.0, AcceptanceCriterion.Status.VERIFIED)
        );

        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                "TASK-4",
                "NON_CODE",
                "IN_PROGRESS",
                new BigDecimal("100.00"),
                criteria,
                null
        );

        ProgressEvaluationResponse res = evaluator.evaluate(input);

        assertThat(res.verifiedProgress()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(res.evaluationStatus()).isEqualTo("VERIFIED");
        assertThat(res.discrepancyDetected()).isFalse();
    }

    @Test
    @DisplayName("Discrepancy detected when claimed progress exceeds verified progress by 25% or more")
    void evaluate_discrepancyDetected_whenClaimedExceedsVerifiedBy25Percent() {
        List<AcceptanceCriterion> criteria = List.of(
                createCriterion(1, 1.0, AcceptanceCriterion.Status.VERIFIED),
                createCriterion(2, 1.0, AcceptanceCriterion.Status.NOT_MET)
        ); // Verified = 50.00%

        // Claimed = 80.00% (delta = 30.00% >= 25.00%)
        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                "TASK-5",
                "NON_CODE",
                "IN_PROGRESS",
                new BigDecimal("80.00"),
                criteria,
                null
        );

        ProgressEvaluationResponse res = evaluator.evaluate(input);

        assertThat(res.verifiedProgress()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(res.discrepancyDetected()).isTrue();
        assertThat(res.evaluationStatus()).isEqualTo("DISCREPANCY_DETECTED");
    }

    @Test
    @DisplayName("CODE task with criteria 100% verified but failing CI flags EVIDENCE_INCOMPLETE")
    void evaluate_codeTask_withFailingCi_flagsEvidenceIncomplete() {
        List<AcceptanceCriterion> criteria = List.of(
                createCriterion(1, 1.0, AcceptanceCriterion.Status.VERIFIED)
        );

        CodeEvidenceDto codeEvidence = new CodeEvidenceDto(
                true,
                42,
                "abc1234567890123456789012345678901234567",
                false, // CI failed!
                "FAILURE",
                true,
                "PASS",
                true
        );

        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                "CODE-1",
                "CODE",
                "IN_REVIEW",
                new BigDecimal("100.00"),
                criteria,
                codeEvidence
        );

        ProgressEvaluationResponse res = evaluator.evaluate(input);

        assertThat(res.verifiedProgress()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(res.evaluationStatus()).isEqualTo("EVIDENCE_INCOMPLETE");
    }

    @Test
    @DisplayName("CODE task with criteria 100% verified and all CI/PR/Review evidence passing status is VERIFIED")
    void evaluate_codeTask_withAllPassedEvidence_statusIsVerified() {
        List<AcceptanceCriterion> criteria = List.of(
                createCriterion(1, 1.0, AcceptanceCriterion.Status.VERIFIED)
        );

        CodeEvidenceDto codeEvidence = new CodeEvidenceDto(
                true,
                101,
                "def1234567890123456789012345678901234567",
                true,
                "SUCCESS",
                true,
                "PASS",
                true
        );

        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                "CODE-2",
                "CODE",
                "IN_REVIEW",
                new BigDecimal("100.00"),
                criteria,
                codeEvidence
        );

        ProgressEvaluationResponse res = evaluator.evaluate(input);

        assertThat(res.verifiedProgress()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(res.evaluationStatus()).isEqualTo("VERIFIED");
        assertThat(res.discrepancyDetected()).isFalse();
    }

    @Test
    @DisplayName("CODE task with missing PR flags EVIDENCE_INCOMPLETE")
    void evaluate_codeTask_withMissingPr_flagsEvidenceIncomplete() {
        List<AcceptanceCriterion> criteria = List.of(
                createCriterion(1, 1.0, AcceptanceCriterion.Status.VERIFIED)
        );

        CodeEvidenceDto codeEvidence = new CodeEvidenceDto(
                false, // No PR linked
                null,
                null,
                false,
                "MISSING_PR",
                false,
                "NO_REVIEW",
                false
        );

        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                "CODE-3",
                "CODE",
                "IN_REVIEW",
                new BigDecimal("100.00"),
                criteria,
                codeEvidence
        );

        ProgressEvaluationResponse res = evaluator.evaluate(input);

        assertThat(res.verifiedProgress()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(res.evaluationStatus()).isEqualTo("EVIDENCE_INCOMPLETE");
    }
}
