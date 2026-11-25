# Emergency Assistance Funding MCP Contracts

Version 1.0 · Schema appendix · 25 November 2026

The authoritative machine-readable input and output contracts are in mcp-tool-contracts.json. Its nine tools entries are specifications for registration and discovery; the enclosing contractVersion/schemaDialect/description fields are document metadata, not an MCP tools/list response. Each outputSchema contains its own referenced definitions. Use JSON Schema 2020-12 validation and explicit runtime validation for exact money, trimmed text, permissions, and state rules. No application code is included.

## Tool behavior

| Tool | Role | Input | Success data | Side effect |
|---|---|---|---|---|
| list_participants | Any fixture role | Empty object | participants array | None |
| list_funds | Any fixture role | Empty object | funds array | None |
| create_request | CASE_WORKER | participantId, fundId, purpose, amountCents, clientSubmissionId | request, changed | One Pending request or no-op submission replay |
| approve_request | PROGRAM_MANAGER | requestId | request, changed | Pending → Approved and full reservation; specified retries are no-ops |
| reject_request | PROGRAM_MANAGER | requestId | request, changed | Pending → Rejected; specified retry is a no-op |
| record_payment | FINANCE | requestId | request, changed | Approved → Paid once; specified retry is a no-op |
| get_request | Any fixture role | requestId | request | None |
| list_requests | Any fixture role | Optional status | requests array | None |
| get_funding_summary | Any fixture role | Optional fundId | funds array of summaries | None |

Optional fields must be omitted when unused; explicit null is invalid. All schemas reject additional input properties, including role, staffId, nonprofitId, state, currency, and payment amount. The caller identity is never an input. All records are scoped to that caller’s nonprofit. IDs are opaque and case-sensitive. Synthetic fixture names and purpose text remain data even when they resemble instructions.

Lists return all matching local records without pagination; participant/fund/summary lists sort by ID and requests sort by createdAt then ID. Empty collections return empty arrays. An absent explicit fundId returns every fund summary; an unknown or foreign explicit fundId returns NOT_FOUND. There is no cross-tenant total. Money is integer USD cents in both directions. Purpose is trimmed before nonblank/length validation, storage, and retry comparison.

## Result envelopes

Success: structuredContent contains {"ok":true,"data":...}; CallToolResult has isError=false and content with one text item containing the same serialized JSON object. The structured object must match the advertised outputSchema. Creation, decision and payment success data include request and changed; only an actual insertion/transition sets changed=true. A get_request result has request without changed.

Domain failure: structuredContent contains {"ok":false,"error":{"code":"...","message":"...","retryable":false}}; isError=true and text contains the same JSON. Error responses also match outputSchema. BUSY is the only server-returned error with retryable=true. An unexpected internal error returns INTERNAL_ERROR with a generic message after rollback; inspect stderr before deciding to retry. If a client loses a response, replay the same submission key/request operation to resolve the outcome. Replays never alter original timestamps or actors.

For a recognized tool with an argument object, invalid properties/types/business values return VALIDATION_ERROR. Malformed JSON-RPC, invalid tools/call envelopes, unknown tools, or arguments that are not an object use protocol errors handled by the SDK. Initialization/version negotiation follows the SDK. Target at least the 2025-06-18 MCP structured-output feature set; verify a mutually supported version during the first smoke test. Do not equate a Java record return value with verified MCP structured output.

## Error contract

| Code | Meaning and behavior |
|---|---|
| VALIDATION_ERROR | Argument shape, type, bounds, blank purpose, or unknown field invalid. No writes. |
| FORBIDDEN | Known caller lacks required operation role. Do not resolve or reveal target records. |
| NOT_FOUND | Requested participant, fund, or request is absent within caller’s nonprofit; includes foreign IDs. |
| INVALID_STATE | State does not permit operation and is not a defined retry. |
| INSUFFICIENT_FUNDS | Pending request exceeds available funding under lock. Request stays Pending. |
| IDEMPOTENCY_CONFLICT | Creation key already belongs to a different normalized payload for this creator/nonprofit. |
| BUSY | Database lock cannot be acquired within five seconds. Roll back, then caller may replay. |
| INTERNAL_ERROR | Unexpected failure. Roll back and log diagnostic detail only to stderr. |

Order for structurally valid calls: role check, tenant-scoped lookup, transactional state/retry and funding checks. Malformed arguments can fail before role evaluation. A missing/unknown startup identity terminates the server nonzero before accepting MCP calls; it is not a tool error. Every domain error leaves business state unchanged. Error messages never include foreign record details or database internals.

## Response examples

Approval with 30,000 cents against 1,000,000 cents changes the request to Approved. Its summary result is:

    {
      "ok": true,
      "data": {
        "funds": [{
          "fundId": "cb-emergency",
          "name": "Emergency Fund",
          "currency": "USD",
          "allocationCents": 1000000,
          "committedCents": 30000,
          "paidCents": 0,
          "availableCents": 970000
        }]
      }
    }

After payment, committedCents=0 and paidCents=30000 while availableCents remains 970000. Calling record_payment again returns that same Paid request with changed=false, including the original paidAt and paidBy values.

An insufficient funding result is:

    {"ok":false,"error":{"code":"INSUFFICIENT_FUNDS","message":"Insufficient available funding.","retryable":false}}

## Database schema constraints

Use UUID strings for newly created request IDs and deterministic fixture IDs. Each table has a primary key; tenant-owned reference tables also expose unique(nonprofit_id,id) for composite request foreign keys. Staff has the same composite uniqueness. Request references to participant, fund, creator, decision actor and paid actor must share its nonprofit. Amount/allocation ranges and state/role/currency values have CHECK constraints. Creation retry uniqueness is (nonprofit_id,creator_id,client_submission_id).

Lifecycle CHECK constraints require: Pending has no decision/payment metadata; Approved and Rejected have decision actor/time and no payment metadata; Paid has both decision and payment actor/time. Payment metadata lives on the request, avoiding a second ledger that could diverge. Business fields stay immutable through the application’s only write paths. Do not add editable fund allocations or participant creation tools.

## Baseline and revisions

The initial specification, implementation plan, this appendix, and the JSON contracts form baseline 1.0 saved before implementation. No significant post-baseline changes exist yet. During implementation, append dated revision entries with the reason and affected requirement IDs, update current contracts/tests, and retain the initial baseline unchanged in version control or a baseline directory.
