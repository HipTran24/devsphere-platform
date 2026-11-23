# Contributing to DevSphere

Thank you for contributing to DevSphere!

## Multi-Role Agentic Workflow

DevSphere uses a three-role development process:
- **Planner**: Maps requirements, allowed file scopes, and acceptance criteria.
- **Implementer**: Implements features, migrations, API controllers, frontend views, and tests.
- **QA & Security Reviewer**: Audits git diffs, verifies regression coverage, and validates security invariants before gated commits.

## Testing and Verification Standards

All pull requests must pass the seven verification gates without skipped tests:
1. **Backend**: `./mvnw verify` (Java 27, Spring Boot, architecture checks).
2. **Frontend Unit**: `npm test` (React Testing Library).
3. **Frontend Lint**: `npm run lint` (ESLint 9).
4. **Frontend Build**: `npm run build` (Next.js production bundle).
5. **Frontend E2E**: `npm run test:e2e -- --forbid-only` (Playwright).
6. **Infrastructure & AI**: `python3 -m unittest discover -s tests/ai` and `python3 -m unittest discover -s tests/infra`.
7. **Disaster Recovery**: Automated backup and restore verification drill (`./infra/backup/restore-verify.sh`).

## Commit Convention
Commits must follow Conventional Commits formatting:
`feat(scope): concise description`, `fix(scope): concise description`, `docs(scope): ...`, `test(scope): ...`.
