package com.project.devsphere.project.application;

import java.util.UUID;

public record ProposePmCommand(UUID candidateUserId, Long expectedVersion) {
}
