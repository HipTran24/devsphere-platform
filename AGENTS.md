# DevSphere agent workflow

This repository uses a sequential 3-role subagent workflow powered by Gemini 3.8 Flash High (`gemini-3.8-flash-high`).
The user explicitly configures 3 distinct subagents for planning/decisions, implementation, and independent QA/review.

- **Planner Subagent** (`gemini-3.8-flash-high`): Owns feature planning, architecture mapping, allowed file scope, exclusions, and acceptance criteria.
- **Implementer Subagent** (`gemini-3.8-flash-high`): Implements approved plans, migrations, services, controllers, and tests via Antigravity CLI / agent tooling, and fixes QA findings.
- **QA & Reviewer Subagent** (`gemini-3.8-flash-high`): Independently audits the codebase, verifies regressions, tests, security, coverage, and diffs before authorizing commits.

Use `python3 scripts/ai/workflow.py` as documented in `docs/ai-workflow.md`.
Run workers sequentially in this checkout.
Workers operate independently. The QA and review subagents remain read-only during audits.
Send requested fixes/tests back to the Implementer subagent, then rerun verify, QA, and final review on the new snapshot.

Before implementation, the Planner writes a concrete plan containing requirements, allowed
files, exclusions, acceptance criteria, and required tests. Normal tasks start from
a clean checkout. Use audit tasks to assess existing or uncommitted project code.
Distinguish pre-existing findings from regressions; unresolved blockers prevent commit.

Required checks: JDK 27 `./mvnw verify`, frontend `npm test`, `npm run lint`,
`npm run build`, `npm run test:e2e`. The runner uses the local JDK/Maven cache when
present. Missing Docker, browser, dependency, authentication, skipped required tests,
or denied actions are blockers, never successful checks. Inspect logs for skipped
tests even when a tool returns exit 0. E2E currently uses mock authentication;
it does not establish a working real backend authentication integration.

Only authorized review approval unlocks the gated commit. Never weaken checks to obtain PASS.
Workflow edits require explicit planning and review. Never read credentials or `.env`
into prompts, logs or commits. Respect nested AGENTS.md guidance for affected files.
Use one workflow runner at a time; stop manual edits while validation/review runs.
