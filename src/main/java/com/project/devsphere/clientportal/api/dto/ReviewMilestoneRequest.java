package com.project.devsphere.clientportal.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewMilestoneRequest(
        @NotBlank(message = "decision must not be blank; expected ACCEPTED or CHANGES_REQUESTED")
        String decision,

        String comments
) {
}
