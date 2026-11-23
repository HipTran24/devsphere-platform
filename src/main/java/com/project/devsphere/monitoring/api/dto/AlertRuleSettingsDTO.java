package com.project.devsphere.monitoring.api.dto;

public record AlertRuleSettingsDTO(
        boolean taskBlockedRuleEnabled,
        boolean scheduleOverdueRuleEnabled,
        boolean deadlineBreachRiskRuleEnabled,
        boolean progressEvidenceMissingRuleEnabled,
        int deadlineWarningDays,
        int overdueGraceMinutes,
        int discrepancyThresholdPercent
) {
    public static AlertRuleSettingsDTO defaults() {
        return new AlertRuleSettingsDTO(
                true,
                true,
                true,
                true,
                3,
                60,
                25
        );
    }
}
