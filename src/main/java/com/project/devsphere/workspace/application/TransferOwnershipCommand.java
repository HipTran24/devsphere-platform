package com.project.devsphere.workspace.application;

import java.util.UUID;

public record TransferOwnershipCommand(UUID targetUserId, Long expectedVersion, String reason) {
}
