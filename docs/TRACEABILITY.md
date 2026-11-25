# Requirement traceability

All production Java files are under src/main/java/org/communitybridge/assistance; tests are under the corresponding src/test/java path. IDs refer to the initial specification. Reports in evidence contain actual execution results.

| IDs | Implementation | Automated verification |
|---|---|---|
| R01 | AssistanceApplication.main; IdentityResolver.resolve; caller bean | BusinessTest.rejectsMissingOrUnknownIdentity; allFixtureIdentitiesAreResolvedServerSide; McpEndToEndIT.invalidIdentityAndUninitializedStorageFailBeforeProtocol |
| R02 | Caller.require; RequestService tenant predicates; composite FKs in schema.sql | BusinessTest.everyRoleCanReadOnlyOwnRecords; crossTenantWritesAndReferencesLeaveBothTenantsUnchanged; databaseEnforcesTenantAndLifecycleConstraints; MCP cross-tenant checks |
| R03 | RequestService.create/replay; submission uniqueness and immutable write paths | BusinessTest.creationNormalizesPurposeAndRejectsConflictingReplay; concurrentDuplicateCreationAndPaymentsHaveOneEffect; discovery asserts no edit tool |
| R04 | RequestService.approve/reject/transition | BusinessTest.rejectDoesNotChangeFundingAndRetryIsNoOp; exactBalanceAllowedAndOverspendingRejectedWithoutMutation; forbiddenStateTransitionsAreAtomic |
| R05 | RequestService.pay/transition; lifecycle metadata checks | BusinessTest.exactLifecycleAndRetryMetadata; concurrentDuplicateCreationAndPaymentsHaveOneEffect |
| R06 | Domain.MAX_CENTS; RequestService.summaries; fund and request row locks | BusinessTest.competingApprovalsBlockOnRealFundLockAndCannotOverspend; ContractTest.monetaryBoundsRemainExactAndInputTextHasNoAuthority |
| R07 | RequestService transactions/replay; McpTools error mapping | BusinessTest.roleRestrictionsApplyBeforeLookupAndOnRetries; rollbackAfterActualWriteRestoresStateAndFunding; concurrentApproveVersusRejectHasOneDecision; lockTimeoutIsRetryableAndLeavesPendingUnchanged |
| R08 | ToolCatalog; McpTools; application.properties; logback-spring.xml | ContractTest parameterized invalidArgumentsCannotMutateState and remaining schema tests; McpEndToEndIT schema/text/structured checks and raw protocol check |
| R09 | Database.open/reset; explicit reset main branch; schema.sql fixtures | BusinessTest.persistentReopenAndExplicitReset; McpEndToEndIT.resetIsSeparateAndNormalProtocolStdoutContainsOnlyJson |
| R10 | Java SDK McpDemoClient; McpEndToEndIT; scripts/demo.sh | Packaged JAR subprocess initialization/discovery and complete role-specific lifecycle; real JDBC concurrency tests |
| R11 | pom.xml; Maven Wrapper; README; docs and evidence | clean verify, demo profile, captured reports/dependency tree; AI evidence records real failures and corrections |

Cross-cutting tests snapshot all request rows before forbidden, cross-tenant, invalid-state, conflicting-key, lock-timeout and injected-failure actions. Funding checks independently verify committed/paid/available totals. Concurrency tests use separate real connections/transactions, bounded waits and explicit synchronization, with no enclosing test transaction.

R05/R07/R09/R10 also map to Database.url (WRITE_DELAY=0) and DurabilityTest.acknowledgedApprovalAndPaymentSurviveImmediateProcessHalt. The test acknowledges an approval/payment in a child JVM, immediately halts without cleanup, reopens the database, and checks the persisted state/balances.
