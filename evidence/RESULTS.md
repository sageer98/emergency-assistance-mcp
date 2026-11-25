# Verification results and timing

Final verification on 25 November 2026: **58 tests passed; zero failures, errors, or skipped tests.**

| Suite | Executions | Result |
|---|---:|---|
| ContractTest | 20 | Passed |
| BusinessTest | 34 | Passed |
| McpEndToEndIT | 3 | Passed |
| DurabilityTest | 1 | Passed |

The final clean build ran through scripts/demo.sh with Maven 3.9.11 and JDK 21.0.7. Maven reported 25.182 seconds for the complete build and test run. The local environment used workspace cache overrides to comply with sandbox write permissions; they are not required on an ordinary checkout.

    JAVA_HOME=<JDK21> MAVEN_USER_HOME=<workspace-cache> ./scripts/demo.sh -B -ntp -Dmaven.repo.local=<workspace-repository> clean

Portable equivalent, from the project directory with Java configured:

    ./mvnw clean verify -Pdemo

The demo negotiated MCP 2025-06-18, discovered all nine schemas, validated structured/text results, created/approved/paid across separate fixture-identity processes, and checked persisted final funding. The exact negotiated version and calls appear in mcp-demo-transcript.json. The final fund has allocation=1000000, committed=0, paid=30000, available=970000 cents.

Financial validation includes real fund-row blocking and competing approvals, concurrent same-key creation and approval/payment retries, opposing decisions, insufficient funding, illegal states, denied roles and tenant access, input boundaries, Unicode purpose length, lock timeout rollback, and an injected failure after a real SQL write. Storage reopening, explicit reset, bad startup identities, and protocol stdout were also checked. No required implemented check remains unrun. Out-of-scope production OAuth/hosting, real payments, and other operating systems/JDKs were not tested.

The log contains an intentional unknown-tool protocol error and SDK shutdown warnings for its terminated child processes; assertions verify these expected paths. Earlier runs are retained: 01 failed client-test compilation; 02 exposed incompatible schema-validator runtime linkage; 03 exposed missing headless ObjectMapper wiring. These issues were corrected. Run 04 passed 56 tests. Run 05 passed the expanded 57 tests but revealed the optional clean argument ran after verify; script ordering was corrected and run 06 rebuilt cleanly while retaining reports and transcript. Packaging review then found that default stdio negotiation was still 2024-11-05. After explicit transport advertisement and stricter assertions, run 07 passed all 57 tests with MCP 2025-06-18. The legacy transcript and SDK default-version bytecode excerpt are preserved for comparison.

Recorded implementation-session elapsed time: 09:47:49–10:03:09 UTC (15.3 minutes), including dependency resolution, coding, verification, documentation and packaging to this record. This is the agent session interval, not a claim about the candidate's human work time. Earlier specification/discussion time was not timed, so a cumulative assessment total is not asserted. The candidate should add their actual earlier time before submission and respect the assessment's three-hour cap.

Known limitations match the specification: trusted local identities, one server JVM per database file, fixture-scale unpaginated lists, USD/full simulated payments, and transition metadata without a separate audit ledger. Next priorities are candidate review and interview preparation, then business clarification of identity/audit requirements before production work. No additional assessment features are needed.

Windows durability follow-up: the user supplied an INVALID_STATE failure at the first finance payment after an apparently successful manager approval. The new regression halts a child JVM immediately after approval/payment acknowledgment without running cleanup. Before the fix it reproduced Approved reverting to Pending; with H2 WRITE_DELAY=0 it passes. Run 10 is the current full verification: 58 passed, including the MCP demo and crash-durability test. A native Windows rerun of this fix has not been performed here and remains for the user. Earlier macOS session timings above remain historical; this follow-up was separate.
