# Emergency Assistance Funding Implementation Plan

Version 1.0 · 25 November 2026 · No application implementation started

Implement against TECHNICAL_SPECIFICATION_v1.md and the companion contracts. Preserve this baseline before writing application code. Target 120 minutes total active work and stop at 180 minutes, including earlier analysis, specification, AI interaction, coding, and verification. Subtract time already spent; do not restart the assessment clock. Track actual work intervals instead of reporting these estimates as actuals.

## Ordered tasks and verification gates

| Task | Budget | Depends on | Completion and verification gate |
|---|---:|---|---|
| P1 Preserve specification and contracts | 20 min | None | Save this baseline; check each assessment requirement has an ID, rule, and test. Resolve ambiguity through stated assumptions. |
| P2 Bootstrap build and MCP smoke test | 15 min | P1 | Pin Java/Boot/AI/Wrapper; resolve dependencies and save tree. Start stdio, initialize client, discover one tool with outputSchema, validate structuredContent, and confirm clean stdout. Record any version change before proceeding. |
| P3 Storage and caller policy | 20 min | P2 | Create schema/constraints, fixture seed/reset mode, trusted identity mapping, tenant-scoped repositories. Verify reset repeatability, restart persistence, missing identity, and foreign references. |
| P4 Business transactions | 25 min | P3 | Implement create/retry, decisions, payment, summary and shared lock order. Verify balances, invalid states, roles, and concurrent approval/payment tests before adding remaining adapters. |
| P5 MCP tools and demo | 15 min | P4 | Register all nine contracts; map domain errors and schema validation. Run sequential subprocess sessions as case worker, manager, finance; discover tools and assert final summary. |
| P6 Final verification and submission | 25 min | P5 | Run clean verification, demo, reset/restart checks; complete traceability with actual symbols and test names, AI evidence, limitations, actual time and exact commands. Report failures or unrun checks. |

The remaining allowance up to the three-hour cap is for unresolved required functionality or validation. If time expires, submit the working subset, list incomplete requirements and failed/unrun checks, and stop. Do not spend it on additional features.

## Test and traceability plan

Names below are planned locations, not evidence of implemented or passing tests. Replace them with verified paths/symbols and actual results in the final submission.

| Requirements | Planned implementation | Planned verification |
|---|---|---|
| R01 | CallerIdentityResolver, fixture staff lookup | IdentityTest: missing/unknown startup fails; six identities map correctly |
| R02 | CallerPolicy, tenant-scoped repositories and composite FKs | TenantIsolationIT: every tool, foreign participant/fund references, lists, retry isolation; PermissionIT: all roles read |
| R03, R07 | RequestService.create, unique submission constraint | CreationIT: immutable payload, exact replay, conflict, simultaneous same-key creation |
| R04, R06 | RequestService.approve/reject, fund and request locks | FundingIT: lifecycle, exact/excess balance, rejection; ApprovalConcurrencyIT: competing funds reservation |
| R05, R07 | RequestService.pay and retry/state handling | PaymentIT: full payment once, preserved approval data; RetryConcurrencyIT: duplicate approval/pay and conflicting decisions |
| R06, R08 | ContractValidator, exact money mapping | ValidationTest: zero/negative/fraction/string/null/overflow, purpose, extra properties, valid boundary |
| R07 | Transaction boundaries and exception mapping | FailureAtomicityIT: state unchanged on every failed operation, including injected exception before commit |
| R08, R10 | McpToolConfiguration, typed result mapper, client | McpEndToEndIT: initialize/discover, schemas, invalid call/error, lifecycle, stderr/stdout separation |
| R09 | SeedCommand, schema and database configuration | StorageIT: seed twice gives same fixtures, reset explicit, normal startup preserves data, reopen persistence |
| R10, R11 | Demo client, README and evidence | Clean setup → verify → demo using documented commands; record exact pass/fail/unrun totals |

Concurrency gate: use two threads, separate connections and real service transactions against one H2 database, no enclosing test transaction. Prepare two Pending 30,000-cent requests against 50,000 cents. Coordinate both workers before service invocation with a barrier; use a latch/hook to hold the first fund lock and prove the other transaction cannot reserve before release. Expect one success and one INSUFFICIENT_FUNDS, never a negative balance. Use bounded waits and record worker exceptions. Also race approval vs rejection and two payments; check one final state, unchanged immutable fields, and one payment effect. No sleep-based assertions or mocked locks.

## Execution and demonstration contract

Implement and verify these entry points from a clean checkout; they are planned commands, not executable in this documentation-only delivery:

    ./mvnw clean verify
    java -jar target/emergency-assistance.jar --app.mode=reset
    STAFF_ID=cb-case-worker java -jar target/emergency-assistance.jar
    ./mvnw -Pdemo verify

APP_DB_PATH defaults to ./data/assistance. Startup opens an existing database only. Reset is a local CLI mode, independent of staff identity/MCP, and refuses an in-use database. Demo explicitly seeds its own temporary database. Package the JAR before subprocess tests in Maven verify. Document prerequisites, shell, working directory, paths, identities, and client configuration.

Seed cb (Community Bridge) and hs (Harbor Support): each has two participants, Emergency Fund=1,000,000 cents, Transport Fund=200,000 cents, and no requests. IDs: cb-p1/cb-p2 and cb-emergency/cb-transport, with equivalent hs IDs. Staff IDs: cb-case-worker, cb-program-manager, cb-finance and hs equivalents.

Demo discovers tools/participants/funds and creates 30,000 cents as cb-case-worker; closes and awaits process exit; approves as cb-program-manager; closes; pays and reads summary as cb-finance. Assert cb-emergency committed=0, paid=30000, available=970000. Always clean up subprocesses. No LLM call required.

## AI use and submission evidence

For later AI coding tasks, give requirement IDs, permitted files, contracts, and one verification gate. Human review owns assumptions, permissions, locking, constraints, and integration. Review diffs and independently execute tests; reject unrelated scope. No delegation is initiated by this plan.

Collect three genuine examples with prompt/context, accepted suggestion/change, and independent evidence: specification checked against the assessment; coding checked against diffs/tests; debugging checked against a reproducer/test output. The latter two remain to be collected. Record corrections only when they occur.

Keep final submission narrative around three pages excluding schemas/code/test output; condense setup/results/AI/time/limitations rather than repeating these planning documents. Include baseline/revisions, source/tests, wrapper/dependency tree, demo, mapping, actual results/time. Deferred: production identity, simultaneous server processes, pagination, independent audit ledger, non-USD/partial/real payments.

Documentation checks: nine unique tool contracts parse; local schema references resolve; R01–R11 appear in specification and test mapping. Full JSON Schema metaschema validation was not run because the validator is unavailable. No build or application test has run; those remain implementation gates.
