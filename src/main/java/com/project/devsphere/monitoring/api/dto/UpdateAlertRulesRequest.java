package com.project.devsphere.monitoring.api.dto;

public record UpdateAlertRulesRequest(
        Boolean taskBlockedRuleEnabled,
        Boolean scheduleOverdueRuleEnabled,
        Boolean deadlineBreachRiskRuleEnabled,
        Boolean progressEvidenceMissingRuleEnabled,
        Integer deadlineWarningDays,
        Integer overdueGraceMinutes,
        Integer discrepancyThresholdPercent
) {
}
