# Specification revisions

The files under initial are the unchanged pre-code baseline. The following implementation clarifications apply to version 1.1; monetary/state/permission/tool contracts remain unchanged.

| Date | Requirements | Change and reason | Verification |
|---|---|---|---|
| 25 November 2026 | R04–R07 | Use Spring TransactionTemplate with JdbcTransactionManager instead of annotation-driven boundaries. Explicit boundaries make duplicate-key rollback and a fresh read transaction visible; READ_COMMITTED and fund-before-request row locks remain as specified. | BusinessTest concurrency, duplicate submission and rollback tests |
| 25 November 2026 | R08 | Use the published JSON Schemas and explicit exact-number checks instead of a second Bean Validation model. Register typed SDK tool specifications directly to preserve outputSchema and structuredContent. Add an explicit Jackson mapper because this headless starter does not provide one. | ContractTest and real MCP output-schema assertions |
| 25 November 2026 | R08, R11 | Pin schema-validator 2.0.0 to match MCP SDK 0.18.3. The initial implementation's 1.5.8 caused a runtime linkage failure exposed by MCP integration tests. Boot/AI/Java pins remain unchanged. | Failed run 02 and subsequent complete passing verification |
| 25 November 2026 | R03, R08 | Interpret the 500-character purpose limit as Unicode code points, matching JSON Schema. Allow 1,000 UTF-16 units in the SQL column and enforce the 500-code-point limit at the validated write boundary. | ContractTest.purposeLimitCountsUnicodeCharactersRatherThanUtf16Units |

| 25 November 2026 | R08, R10 | Override the SDK stdio transport protocol advertisement to 2025-06-18 on both server and Java demo client. The SDK stdio defaults advertised only 2024-11-05 despite implementing structured results. Require the exact negotiated version in both SDK and raw-wire tests. | Preserved legacy transcript, final packaged-server protocol and schema assertions |

| 25 November 2026 | R05, R07, R09, R10 | Set H2 WRITE_DELAY=0. A Windows demo failure exposed that delayed writes could lose an acknowledged approval when the MCP client terminates the process. A child-JVM regression reproduced Approved reverting to Pending with Runtime.halt before the fix; both approval and payment persist after the fix. | DurabilityTest.acknowledgedApprovalAndPaymentSurviveImmediateProcessHalt; logs 08–10 |

The lock verification uses an independent real transaction holding the fund row while both competing service calls reach their lock statements. Both block until release; one then approves and the other returns INSUFFICIENT_FUNDS. This avoids mocked locks and timing sleeps.
