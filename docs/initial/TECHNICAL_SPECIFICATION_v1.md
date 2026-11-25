# Emergency Assistance Funding Technical Specification

Version 1.0 · Initial specification · 25 November 2026 · Saved before implementation

Build a headless Java and Spring Boot MCP server so nonprofit staff can submit, decide, pay, and retrieve assistance requests without overspending or exposing another nonprofit’s records. The initial baseline is preserved in this file; record significant later changes in a dated revision entry with the affected requirement, reason, and verification impact.

## Requirements and scope

| ID | Required behavior |
|---|---|
| R01 | Resolve a trusted startup identity to exactly one nonprofit and staff role; reject missing or unknown identities. |
| R02 | All roles read their nonprofit’s participants, funds, requests, and funding summaries. Every record reference and query enforces ownership. |
| R03 | Only case workers create immutable Pending requests for an existing participant and fund in their nonprofit. |
| R04 | Only program managers approve or reject Pending requests. Approval reserves the entire amount atomically if affordable; rejection changes no funding. |
| R05 | Only finance staff record one full simulated payment per Approved request, transitioning it to Paid. |
| R06 | USD integer cents and derived balances are exact; competing approvals cannot overspend. |
| R07 | Approval, rejection, payment, and creation retries have the defined duplicate behavior; invalid or forbidden actions leave business state unchanged. |
| R08 | Discoverable stdio MCP tools publish validated input/output schemas and structured results; application logs never enter stdout. |
| R09 | Persist locally across restarts; provide separate repeatable reset/seed for two nonprofits and six fixture staff identities. |
| R10 | Verify requirements with automated tests, including concurrency and actual MCP subprocess calls; provide a reproducible role-correct demo. |
| R11 | Supply pinned dependencies, exact commands, traceability, actual results/time, limitations, and three evidenced AI-use examples. |

No UI, REST API, hosted LLM, cloud deployment, real payments, OAuth implementation, MCP resources/prompts, participant intake, partial payments, refunds, cancellations, or post-submission edits. Plan for two hours; stop at three hours of cumulative active assessment work, including specification and AI interaction.

## Architecture and dependencies

Use Java 21, Spring Boot 3.5.16, Spring AI BOM 1.1.8 with its stdio MCP starter, Spring JDBC, Bean Validation, file-backed H2, JUnit 5, and Maven Wrapper 3.9.11. These baseline pins require the initial build gate. Use Boot/AI dependency management and save the resolved dependency tree. No snapshots or version ranges.

MCP adapter → caller policy → transactional services → JDBC repositories. Publish explicit MCP tool specifications if annotation conversion cannot preserve output schemas/structured content. Use externally invoked Spring transactional methods, parameterized SQL, and record DTOs. No JPA needed.

Embedded H2 allows one server JVM per file; demo identity sessions run sequentially. Startup requires an initialized database, never seeds/resets, disables web/banner, enables only MCP tools, and logs to stderr. Concurrent in-process operations use separate connections.

## Business behavior and transactions

Allowed transitions: Pending → Approved, Pending → Rejected, Approved → Paid. All others fail with INVALID_STATE except the explicit no-op retries below. Submission leaves funds unchanged. For each fund: committed = sum(Approved amounts); paid = sum(Paid amounts); available = allocation − committed − paid. Pending and Rejected contribute zero. Do not persist duplicate balance counters.

Use JSON integer cents, SQL BIGINT, and Java long, bounded by 9,007,199,254,740,991 for exact JSON interoperability. Requests must be positive; allocations allow zero. Reject fractional/coerced/out-of-range inputs; use checked/exact arithmetic. Currency is USD. Trim purpose and require 1–500 characters. Validate IDs and unknown fields according to MCP_CONTRACTS.md and mcp-tool-contracts.json.

For every decision/payment, authenticate, authorize, resolve the request using nonprofit plus ID, then lock its fund row followed by its request row with SELECT FOR UPDATE inside a READ_COMMITTED transaction. Re-read request state after locking. All these operations follow the same lock order. Approval computes balances after acquiring the fund lock, verifies sufficient funds, then changes state and decision metadata. Payment updates Paid state and payment metadata in that same transaction. A single aggregate query produces a consistent summary. A 5-second lock timeout rolls back and returns retryable BUSY. No automatic application retry. Database locking, rather than an in-memory mutex or @Transactional alone, serializes competing financial changes.

Approval of Approved or Paid, rejection of Rejected, and payment of Paid return current data with changed=false. They create no new transition, timestamp, or financial effect. Approval of Rejected, rejection of Approved/Paid, and payment of Pending/Rejected fail. Authorization and ownership checks precede retry handling. New transitions return changed=true.

Creation requires a clientSubmissionId unique within nonprofit plus creator. Under a unique database constraint, replaying the same key and normalized participant/fund/purpose/amount returns the original request, regardless of its current status; a different payload returns IDEMPOTENCY_CONFLICT. Handle concurrent unique-key conflicts after rolling back the failed transaction, then read and compare the winner in a fresh transaction. A new key deliberately creates a distinct request. There is no business rule deduplicating similar assistance requests.

## Data ownership and permissions

Entities: Nonprofit; Staff(role, nonprofit); Participant(nonprofit); Fund(nonprofit, fixed allocation); AssistanceRequest(nonprofit, participant, fund, creator, submission key, purpose, cents, status, UTC creation/decision/payment actors and timestamps). The appendix defines fields and constraints. Composite tenant foreign keys prevent mismatched references; unique(nonprofit,creator,submission key) prevents duplicate submission. A Paid request is the single simulated payment record. Preserve approval metadata after payment. No public path edits submitted business fields.

All roles read; CASE_WORKER creates; PROGRAM_MANAGER approves/rejects; FINANCE pays. Select STAFF_ID at startup and derive immutable caller context server-side. Every query, including retries, scopes to caller nonprofit. Foreign IDs and absent IDs both return NOT_FOUND. Tool arguments, stored text, and prompts cannot grant permissions. Trusted local fixture selection is not production authentication.

## MCP contract and errors

The companion MCP_CONTRACTS.md and mcp-tool-contracts.json define the nine tool descriptions, schemas, side effects, and errors. All successful results carry structuredContent matching outputSchema and a JSON text equivalent; domain failures use isError=true and the same typed error envelope. Unknown tools or malformed protocol requests use MCP/JSON-RPC errors. Do not expose SQL, stack traces, foreign IDs, or balances in errors.

## Acceptance criteria

| Check | Expected result |
|---|---|
| $10,000 fund, create $300 | Pending; available $10,000, committed/paid $0 |
| Approve then pay that request | After approval: available $9,700, committed $300. After pay: available $9,700, committed $0, paid $300 |
| Reject Pending | Rejected; funding unchanged |
| Two $300 approvals against $500 concurrently | Exactly one Approved; other INSUFFICIENT_FUNDS; available $200 |
| Repeat approval/payment, including concurrent retries | At most one transition each; balances and original metadata unchanged on replay |
| Illegal transition, wrong role, foreign participant/fund/request | Defined error; database business rows unchanged |
| Missing/unknown identity; invalid amount/purpose | Startup refused or VALIDATION_ERROR respectively; no business mutation |
| Create retry same key, then conflicting payload | Original ID returned, then IDEMPOTENCY_CONFLICT; one row |
| Restart, discover tools, role-correct MCP lifecycle | State persists; all nine schemas discoverable; final summary matches balances |

## Assumptions and production security

Clarify with the business: retry semantics, submission deduplication, grant eligibility, mandatory rejection reasons, multiple memberships, audit retention, and simultaneous server processes. Proceed with the defined no-op retries and submission key, no extra eligibility rules/rejection reason, one nonprofit/role per fixture, transition metadata without a separate audit ledger, and sequential server sessions.

For production HTTP, use the existing OAuth provider over TLS. Validate token issuer, audience, signature/allowed algorithm, expiry/not-before and scopes through trusted metadata/JWKS; introspect opaque tokens. Map validated subject to active server-managed membership and roles; enforce tenant/business permissions on every call. Rotate keys, never log/pass through tokens, and supply MCP protected-resource metadata and authentication challenges. OAuth implementation is deferred. See [MCP authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization).

## Technical references

- [Spring AI 1.1 compatibility](https://docs.spring.io/spring-ai/reference/1.1/getting-started.html) and [stdio server configuration](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-stdio-sse-server-boot-starter-docs.html)
- [Spring Boot 3.5 Java and build requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [H2 storage and connection modes](https://h2database.com/html/features.html) and [locking commands](https://h2database.com/html/commands.html#select)
- [MCP tools and structured results](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
