# Database verification report

## M1.1 registration and OTP

The current registration migration is `V007__queue_email_verification.sql`. SHA-256 is `b29f2d716efb5f93542675d5d7d558198c3b8fa2c772fbbff6970dd8294cc366`.

Current M1.1 verification comprises 61 Maven tests with 0 failures, 0 errors, and 0 skipped, encompassing 52 PostgreSQL integration cases. All seven migrations, including V007, along with fresh, sequential, and idempotent reruns, are verified on Testcontainers PostgreSQL 18.7.

## Historical V001–V006 persistence baseline

Persistence suite verified at 2026-11-20T16:26:48+07:00. Native setup and packaged application startup verified at 2026-11-20T16:28:09+07:00. The live database measurements and exported ERD artifacts below record that V006 snapshot.

### Automated persistence tests

`mvn verify`: 34 tests, 0 failures, 0 errors, 0 skipped.

Tests use isolated PostgreSQL 18.7 Testcontainers. Fresh migration, sequential upgrades and zero-pending re-run are included.

### Native setup verification

- Removed the five development scripts, bytecode cache and diagram-only dependency manifest. Setup, application startup and persistence tests now use Docker/SQL and Java/Maven.
- `docker compose config --quiet` passed.
- A fresh Testcontainers volume ran `infra/postgres/runtime-role.sql` through the official image's initialization mechanism. Runtime login succeeded with a password containing a single quote and dollar sign.
- `docker compose up -d --wait database` preserved the existing local data volume; `docker compose run --rm database-setup` successfully reapplied runtime-role setup.
- The Java Flyway callback applied table privileges after migration, including a run with zero pending migrations. Restricted runtime permissions and rejection of missing/owner runtime roles passed.
- The packaged application started successfully against `Devphere-DB` using the runtime account, loaded local configuration and passed Hibernate mapping validation. The verification process exited normally without opening HTTP.
- This baseline run verified migration SQL files V001–V006 and the exported ERD artifacts.

### Live Devphere-DB checks

| Check | Result |
|---|---|
| tables | `36` |
| foreignKeys | `120` |
| indexes | `248` |
| migrations | `6` |
| invalidConstraints | `0` |
| runtimeSuperuser | `False` |
| runtimeCanCreate | `False` |
| runtimeCanUpdateAudit | `False` |
| runtimeCanTruncateAudit | `False` |
| runtimeCanReadFlyway | `False` |
| timezone | `UTC` |
| Container health | `healthy` |
| Restricted-role audited write and deferred constraints | `PASS; rolled back` |
| Runtime CREATE, audit UPDATE, audit TRUNCATE, Flyway SELECT | `42501 denied` |
| Smoke-test rows persisted | `0` |

### Migration artifacts

| Migration | SHA256 |
|---|---|
| `V001__identity_and_workspaces.sql` | `528a6e1556c4d6bc9145b4b7dbc311dc454b88cdf0cb92232af39fed0329ce73` |
| `V002__projects_scrum_and_multiple_roles.sql` | `7c76eaa7404a1682e29f12ab7346cb86e59ead18c7c2b07378e6428fd4b65fc8` |
| `V003__capacity_proposals_and_schedule.sql` | `1a656d78321f1f32ec5e1db34ece38667eb27bf14d4ae4aaa90ff98a2227b3e8` |
| `V004__github_and_code_quality.sql` | `ca78c5e2b3cf2620bfaf42fda30e1e30a0925a3f144337088ca4795dfba57f73` |
| `V005__collaboration_ai_and_operations.sql` | `8050c8512037961a9960547015bcaa8c0b0e822aea15f8260b85939169302d56` |
| `V006__tenant_integrity_and_workflow_guards.sql` | `b4d96b0098a774f8aac788704b78f34cbd3e3e648a8f6bda04f9925521373203` |

### Executed scenarios

- activationRequiresBacklogAndPmTransferIsAtomic
- aiGenerationSuccessDoesNotAuthorizeReportPublication
- alertsDeduplicateAndIdempotencyIsTenantScoped
- approvedRequirementAndScheduleHistoryAreImmutable
- attachmentsRequireExactlyOneOwnerMatchingKindAndCleanClientFiles
- auditIsAppendOnlyAndDoesNotRetainSecretHashes
- capacityRejectsOverlappingPeriodsSlotsAndInvalidNumbers
- capacityValueObjectsRoundTripWithoutLosingTimezoneOrSkillTypes
- chatChecksScopeMembershipClientBoundaryAndReplyChannel
- closeSprintMovesUnfinishedTasksBackToBacklogAndFreezesMetrics
- commandIsAtomicIdempotentAndRejectsStaleVersions
- concurrentTaskKeysAreUniqueAndMonotonic
- confirmedDeadlineAppliesAndChangingDraftClearsPriorConsent
- deadlineRequiresConfirmedAppliedProposal
- dependencyDagRejectsSequentialAndConcurrentCycles
- eachMigrationUpgradesFromItsPredecessor
- emailIsCaseInsensitiveAndOtpCannotBeReusedOrExtended
- jobLeasesHaveOneOwnerAndRecoverAfterExpiry
- jpaRoundTripsJsonArraysDecimalsInetAndOptimisticVersions
- lockingPmHoldsProjectAndRevokesSessions
- migratesThirtySixTablesAndValidatesEveryJpaEntity
- milestoneAcceptanceRequiresClientAndPublishedScope
- multipleRolesButOnlyOnePmAndNoDuplicateActiveRole
- nativeBootstrapAndFlywayCallbackKeepRuntimePrivilegesRestricted
- oldShaReviewIsNotCurrentAndAuthorCannotApproveOwnPr
- oneProjectPerWorkspaceAndNoCrossProjectRelations
- ownerMustExistAndMatchWorkspacePointerAtCommit
- pmNominationKeepsOldPmUntilRecipientAccepts
- proposalsAreDraftsAndRejectNegativeEstimatesOrDependencyCycles
- removingWorkspaceMemberRevokesProjectRolesAndChannelMembership
- scheduleRejectsOverlapAndChangesToFrozenBaselineEntries
- taskWorkflowRequiresPmAndFreezesDoneScope
- tenantAndUserBindingsCannotBeMoved
- webhookAndNotificationDuplicatesAreRejected
