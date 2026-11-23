-- V010__unique_schedule_version_proposal.sql
-- Enforce single baseline materialization per proposal and fast lookups

CREATE UNIQUE INDEX IF NOT EXISTS uq_schedule_versions_proposal_id
    ON schedule_versions (proposal_id)
    WHERE proposal_id IS NOT NULL;
