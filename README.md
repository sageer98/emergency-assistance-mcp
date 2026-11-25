# Emergency Assistance Funding MCP Server

A headless Java 21 and Spring Boot application for two fictional nonprofits. Nine MCP tools support participant and fund lookup, immutable assistance requests, manager approval or rejection, full simulated payments, request retrieval, and funding summaries.

The server communicates over standard input/output using MCP 2025-06-18. No frontend, model API key, Docker installation, or external database is required.

## Windows prerequisites

- JDK 21. The project compiles for Java 21.
- Windows PowerShell and internet access for the first dependency download.
- The extracted source project, including `mvnw.cmd`, `.mvn`, `pom.xml`, and `src`.

Maven Wrapper downloads the pinned Maven 3.9.11 distribution, so a separate Maven installation is unnecessary. Extract into a short folder such as `C:\ea\emergency-assistance` to avoid Windows path-length errors.

## Windows quick start

### 1 Open PowerShell in the project folder

Use your actual extracted location. For example:

```powershell
Set-Location "C:\ea\emergency-assistance"
Get-ChildItem
```

Confirm that `pom.xml` and `mvnw.cmd` are listed. A path containing spaces also works when quoted:

```powershell
Set-Location "C:\emergency-assistance"
```

### 2 Configure Java for the current terminal

Replace this example JDK path if your installation is elsewhere:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Test-Path "$env:JAVA_HOME\bin\java.exe"
java -version
.\mvnw.cmd -version
```

`Test-Path` should return `True`. Java and Maven should report the selected JDK 21 installation. `JAVA_HOME` points to the JDK directory, without `\bin` or `\java.exe`.

Optionally save `JAVA_HOME` for future terminals:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", $env:JAVA_HOME, "User")
```

Restart IntelliJ after changing persistent environment variables so its terminal and Maven processes can inherit them. The `$env:` assignments above apply immediately to the current PowerShell session.

### 3 Build and run the MCP demo

```powershell
.\mvnw.cmd clean verify -Pdemo
```

This single command compiles the application, packages the executable JAR, runs business and contract tests, and runs the real MCP client/server integration tests. Do not start a separate server or reset your normal database first; the automated demo creates and seeds its own temporary database.

Expected final output includes:

```text
MCP DEMO PASSED: discovered 9 tools; Pending -> Approved -> Paid;
available=$9,700, paid=$300, committed=$0.
BUILD SUCCESS
```

The updated project contains 58 test executions, including an abrupt-process-exit durability regression. If you applied only the one-line database fix to the original project, it still contains 57 tests until you also add `DurabilityTest.java`.

### 4 Inspect the demonstration results

```powershell
Get-Content .\target\mcp-demo-transcript.json
Get-ChildItem .\target\surefire-reports
Get-ChildItem .\target\failsafe-reports
```

The transcript records discovered tools, identities, inputs, responses, and negotiated protocol version. Tests named `IT` execute during Maven `verify`, after the runnable JAR has been packaged. Running only `test` omits those MCP integration tests.

## What the demo verifies

The Java MCP SDK client launches separate server sessions against one temporary database:

1. `cb-case-worker` discovers tools, looks up records, and submits a $300 Pending request.
2. `cb-program-manager` approves it, reserving $300.
3. `cb-finance` records the full simulated payment and reads the final summary.
4. `hs-program-manager` attempts cross-nonprofit access and receives `NOT_FOUND`.

Sessions close before the next identity starts. The demo also checks duplicate creation, approval/payment retries, invalid input, and forbidden operations. Normal local data is untouched and no language model is called.

| Stage | Committed | Paid | Available |
|---|---:|---:|---:|
| Submitted | $0 | $0 | $10,000 |
| Approved | $300 | $0 | $9,700 |
| Paid | $0 | $300 | $9,700 |

## Windows durability fix

In `src/main/java/org/communitybridge/assistance/Database.java`, the database URL must include:

```java
+ ";LOCK_TIMEOUT=5000;WRITE_DELAY=0";
```

The updated source already includes this setting. If your older copy ends with only `LOCK_TIMEOUT=5000`, append `;WRITE_DELAY=0` and rebuild.

The earlier Windows demo failed at payment with `INVALID_STATE` after a successful approval. A regression test reproduced an acknowledged approval reverting to Pending after immediate JVM termination. `WRITE_DELAY=0` ensures the commit is persisted before returning, rather than relying on later background writes or shutdown cleanup. The regression also checks payment persistence.

The corrected full suite passed on macOS with abrupt child-JVM termination. A native Windows rerun is still needed to confirm the fix on your machine.

## Run a persistent local server on Windows

This section is for connecting your own MCP client. It is separate from the automated demo.

First build and verify the project:

```powershell
.\mvnw.cmd clean verify
```

Choose an absolute database base path without the `.mv.db` suffix:

```powershell
$env:APP_DB_PATH = "C:\ea-data\assistance"
```

Initialize the synthetic database explicitly:

```powershell
java -jar .\target\emergency-assistance.jar --app.mode=reset
```

Reset replaces the database at `APP_DB_PATH`; stop any server using it before resetting. Normal startup never seeds or resets data.

Start as a case worker:

```powershell
$env:STAFF_ID = "cb-case-worker"
java -jar .\target\emergency-assistance.jar
```

The server waits for MCP JSON-RPC messages; it is not an interactive text prompt and has no browser page or HTTP port. An MCP client normally starts this process itself. Stop a manually launched process with `Ctrl+C` before allowing a client to open the same database.

Without `APP_DB_PATH`, storage defaults to `data\assistance.mv.db` relative to the server's working directory. Use the same absolute `APP_DB_PATH` during reset and client startup to avoid selecting different databases.

## Windows MCP client configuration

For clients that use the `mcpServers` configuration format, adapt this example. Replace the Java, project, and database paths with your own values. JSON requires doubled backslashes in Windows paths.

```json
{
  "mcpServers": {
    "emergency-assistance": {
      "command": "C:\\Program Files\\Java\\jdk-21.0.12\\bin\\java.exe",
      "args": [
        "-jar",
        "C:\\ea\\emergency-assistance\\target\\emergency-assistance.jar"
      ],
      "env": {
        "STAFF_ID": "cb-case-worker",
        "APP_DB_PATH": "C:\\ea-data\\assistance"
      }
    }
  }
}
```

Initialize this database once with the reset command before connecting. Only one server JVM can open a database file at a time. Close the existing session before changing `STAFF_ID` and reconnecting as another role.

| Nonprofit | Case worker | Program manager | Finance |
|---|---|---|---|
| Community Bridge | `cb-case-worker` | `cb-program-manager` | `cb-finance` |
| Harbor Support | `hs-case-worker` | `hs-program-manager` | `hs-finance` |

All roles can read their nonprofit's records. Case workers create requests, managers approve/reject, and finance records payments. Missing or unknown identities fail startup. Each nonprofit has two participants and funds allocated $10,000 and $2,000.

## Open in IntelliJ IDEA on Windows

1. Choose **File → Open** and select the folder containing `pom.xml`.
2. Allow IntelliJ to load the Maven project.
3. Set **Project SDK** to JDK 21 under **File → Project Structure → Project**.
4. Set the **Maven runner JRE** to the project JDK or JDK 21 in IntelliJ settings.
5. Open IntelliJ's terminal, confirm Java as shown above, and run:

```powershell
.\mvnw.cmd clean verify -Pdemo
```

## Troubleshooting

| Symptom | Action |
|---|---|
| `JAVA_HOME` invalid or Java cannot be found | Set it to the actual JDK directory; verify `Test-Path` and `.\mvnw.cmd -version`. |
| `mvnw.cmd` not recognized | Use `.\mvnw.cmd` from the project folder and confirm the ZIP was fully extracted. |
| Windows extraction error `0x80010135` | Cancel extraction and extract the original ZIP to a short destination such as `C:\ea`. |
| Finance receives `INVALID_STATE` in the original demo | Confirm `WRITE_DELAY=0` in `Database.java`, then rerun `clean verify -Pdemo`. |
| Database missing at normal startup | Reset the intended database explicitly, using the same `APP_DB_PATH` as the server/client. |
| Database already in use | Close the other MCP session or Java process using that file. |
| Server appears to wait silently | This is expected for a stdio server; connect an MCP client. |
| `Unknown tool` error in automated test output | The demo intentionally calls an unknown tool. Check the final test summary and `BUILD SUCCESS`. |

The SDK can emit child-process termination warnings during test cleanup. An assertion failure, nonzero test failure/error count, or `BUILD FAILURE` requires investigation; do not skip tests to obtain a passing submission.

## macOS and Linux commands

With JDK 21 configured, use the executable shell wrapper:

```sh
./mvnw clean verify -Pdemo
# Equivalent demo script:
./scripts/demo.sh clean
```

For a persistent database and manually launched server:

```sh
java -jar target/emergency-assistance.jar --app.mode=reset
STAFF_ID=cb-case-worker java -jar target/emergency-assistance.jar
```

## Design and submission guide

Money is stored as integer USD cents. Available funding equals allocation minus Approved amounts minus Paid amounts. Payment moves committed funding into paid expenditure without reserving it twice. Spring transactions lock the fund before the request, so competing approvals cannot overspend.

Creation requires `clientSubmissionId`, unique within nonprofit and creator. Identical retries return the existing request; conflicting payloads return `IDEMPOTENCY_CONFLICT`. Defined approval/rejection/payment retries return `changed=false` and preserve original metadata. Caller identity, role, and nonprofit are resolved server-side; tool arguments cannot grant permissions.

The nine tools are `list_participants`, `list_funds`, `create_request`, `approve_request`, `reject_request`, `record_payment`, `get_request`, `list_requests`, and `get_funding_summary`. Application logs go to stderr; stdout is reserved for MCP protocol output.

- [Initial specification and implementation plan](docs/initial/) preserve the pre-code baseline.
- [MCP contract appendix](docs/initial/MCP_CONTRACTS.md) and [current schemas](src/main/resources/mcp-tool-contracts.json) define tool behavior.
- [Revisions](docs/REVISIONS.md), [traceability](docs/TRACEABILITY.md), and [AI workflow](docs/AI_WORKFLOW.md) document implementation decisions and evidence.
- [Verification results](evidence/RESULTS.md) and [resolved dependencies](evidence/dependencies.txt) record actual checks and versions. Archived results describe the included project revision; compare them with your current local run.

Pinned versions: Spring Boot 3.5.16, Spring AI 1.1.8, MCP Java SDK 0.18.3, H2 2.3.232, JSON Schema validator 2.0.0, and Maven 3.9.11. Parent/BOM dependency management pins transitive dependencies.

Limitations: trusted local fixture identities, one server JVM per database file, unpaginated local lists, USD only, full simulated payments, and transition metadata without a separate audit ledger. Frontend development, production OAuth implementation, real payments, cloud hosting, partial payments, cancellations, and refunds are outside scope.