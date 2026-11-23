package com.project.devsphere.planning.infrastructure.persistence;

import com.project.devsphere.planning.domain.PlanProposal;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.UUID;

public interface PlanProposalRepository extends ProjectScopedRepository<PlanProposal> {

    List<PlanProposal> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
