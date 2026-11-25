package org.communitybridge.assistance;

import static org.assertj.core.api.Assertions.*;
import static org.communitybridge.assistance.Domain.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class BusinessTest {
  @TempDir Path temp;
  TestDatabase db;

  @BeforeEach
  void open() throws Exception {
    db = new TestDatabase(temp.resolve("funding"));
  }

  @AfterEach
  void close() {
    db.close();
  }

  private void code(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  private FundSummary summary() {
    return db.service.summaries(db.cw, "cb-emergency").getFirst();
  }

  @Test
  void exactLifecycleAndRetryMetadata() {
    String id = db.create("rent-1", 30000);
    assertThat(db.service.get(db.cw, id).status()).isEqualTo("Pending");
    assertThat(summary())
        .extracting(
            FundSummary::committedCents, FundSummary::paidCents, FundSummary::availableCents)
        .containsExactly(0L, 0L, 1000000L);
    var approved = db.service.approve(db.manager, id);
    assertThat(approved.changed()).isTrue();
    assertThat(summary())
        .extracting(
            FundSummary::committedCents, FundSummary::paidCents, FundSummary::availableCents)
        .containsExactly(30000L, 0L, 970000L);
    assertThat(db.service.approve(db.manager, id)).isEqualTo(new Change(approved.request(), false));
    var paid = db.service.pay(db.finance, id);
    assertThat(paid.changed()).isTrue();
    assertThat(paid.request().decisionAt()).isEqualTo(approved.request().decisionAt());
    assertThat(paid.request().paidBy()).isEqualTo(db.finance.id());
    assertThat(db.service.pay(db.finance, id)).isEqualTo(new Change(paid.request(), false));
    assertThat(db.service.approve(db.manager, id)).isEqualTo(new Change(paid.request(), false));
    assertThat(summary())
        .extracting(
            FundSummary::committedCents, FundSummary::paidCents, FundSummary::availableCents)
        .containsExactly(0L, 30000L, 970000L);
    assertThat(db.service.create(db.cw, db.submission("rent-1", 30000)))
        .isEqualTo(new Change(paid.request(), false));
  }

  @Test
  void rejectDoesNotChangeFundingAndRetryIsNoOp() {
    String id = db.create("rejected", 30000);
    var before = summary();
    var rejected = db.service.reject(db.manager, id);
    assertThat(rejected.request().status()).isEqualTo("Rejected");
    assertThat(db.service.reject(db.manager, id)).isEqualTo(new Change(rejected.request(), false));
    assertThat(summary()).isEqualTo(before);
  }

  @Test
  void exactBalanceAllowedAndOverspendingRejectedWithoutMutation() {
    String expensive = db.create("too-much", 1000001), exact = db.create("exact", 1000000);
    var before = db.snapshot();
    code("INSUFFICIENT_FUNDS", () -> db.service.approve(db.manager, expensive));
    assertThat(db.snapshot()).isEqualTo(before);
    db.service.approve(db.manager, exact);
    assertThat(summary().availableCents()).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "Pending,Paid",
    "Rejected,Approved",
    "Rejected,Paid",
    "Approved,Rejected",
    "Paid,Rejected"
  })
  void forbiddenStateTransitionsAreAtomic(String source, String target) {
    String id = db.create("state", 1);
    switch (source) {
      case "Rejected" -> db.service.reject(db.manager, id);
      case "Approved" -> db.service.approve(db.manager, id);
      case "Paid" -> {
        db.service.approve(db.manager, id);
        db.service.pay(db.finance, id);
      }
    }
    var before = db.snapshot();
    var balance = summary();
    code(
        "INVALID_STATE",
        () -> {
          switch (target) {
            case "Approved" -> db.service.approve(db.manager, id);
            case "Rejected" -> db.service.reject(db.manager, id);
            default -> db.service.pay(db.finance, id);
          }
        });
    assertThat(db.snapshot()).isEqualTo(before);
    assertThat(summary()).isEqualTo(balance);
  }

  @ParameterizedTest
  @CsvSource({
    "cb-case-worker,approve",
    "cb-case-worker,reject",
    "cb-case-worker,pay",
    "cb-program-manager,create",
    "cb-program-manager,pay",
    "cb-finance,create",
    "cb-finance,approve",
    "cb-finance,reject"
  })
  void roleRestrictionsApplyBeforeLookupAndOnRetries(String identity, String operation) {
    String id = db.create("roles", 1);
    db.service.approve(db.manager, id);
    db.service.pay(db.finance, id);
    var caller = db.caller(identity);
    var before = db.snapshot();
    for (String target : List.of(id, "absent"))
      code(
          "FORBIDDEN",
          () -> {
            switch (operation) {
              case "create" -> db.service.create(caller, db.submission("forbidden", 1));
              case "approve" -> db.service.approve(caller, target);
              case "reject" -> db.service.reject(caller, target);
              default -> db.service.pay(caller, target);
            }
          });
    assertThat(db.snapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"cb-case-worker", "cb-program-manager", "cb-finance"})
  void everyRoleCanReadOnlyOwnRecords(String identity) {
    String own = db.create("own", 1);
    var foreign =
        db.service
            .create(db.other, new Submission("hs-p1", "hs-emergency", "Utilities", 100, "other"))
            .request()
            .id();
    var c = db.caller(identity);
    assertThat(db.service.participants(c))
        .extracting(Participant::id)
        .containsExactly("cb-p1", "cb-p2");
    assertThat(db.service.funds(c))
        .extracting(Fund::id)
        .containsExactly("cb-emergency", "cb-transport");
    assertThat(db.service.list(c, null)).extracting(Request::id).containsExactly(own);
    assertThat(db.service.list(c, "Paid")).isEmpty();
    assertThat(db.service.summaries(c, null))
        .extracting(FundSummary::fundId)
        .containsExactly("cb-emergency", "cb-transport");
    code("NOT_FOUND", () -> db.service.get(c, foreign));
    code("NOT_FOUND", () -> db.service.summaries(c, "hs-emergency"));
  }

  @Test
  void crossTenantWritesAndReferencesLeaveBothTenantsUnchanged() {
    String foreign =
        db.service
            .create(db.other, new Submission("hs-p1", "hs-emergency", "Bus", 100, "key"))
            .request()
            .id();
    var before = db.snapshot();
    code("NOT_FOUND", () -> db.service.approve(db.manager, foreign));
    code("NOT_FOUND", () -> db.service.reject(db.manager, foreign));
    code("NOT_FOUND", () -> db.service.pay(db.finance, foreign));
    code(
        "NOT_FOUND",
        () ->
            db.service.create(
                db.cw, new Submission("hs-p1", "cb-emergency", "Rent", 1, "bad-participant")));
    code(
        "NOT_FOUND",
        () ->
            db.service.create(
                db.cw, new Submission("cb-p1", "hs-emergency", "Rent", 1, "bad-fund")));
    assertThat(db.snapshot()).isEqualTo(before);
    assertThat(db.service.summaries(db.other, "hs-emergency").getFirst().availableCents())
        .isEqualTo(1000000);
  }

  @Test
  void creationNormalizesPurposeAndRejectsConflictingReplay() {
    var raw = new Submission("cb-p1", "cb-emergency", " Rent ", 10, "key");
    var created = db.service.create(db.cw, raw);
    assertThat(created.request().purpose()).isEqualTo("Rent");
    assertThat(db.service.create(db.cw, db.submission("key", 10)))
        .isEqualTo(new Change(created.request(), false));
    var before = db.snapshot();
    code("IDEMPOTENCY_CONFLICT", () -> db.service.create(db.cw, db.submission("key", 11)));
    assertThat(db.snapshot()).isEqualTo(before);
    assertThat(db.service.create(db.cw, db.submission("new-key", 10)).request().id())
        .isNotEqualTo(created.request().id());
    assertThat(
            db.service
                .create(db.other, new Submission("hs-p1", "hs-emergency", "Rent", 10, "key"))
                .changed())
        .isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"unknown", " ", "CB-CASE-WORKER"})
  void rejectsMissingOrUnknownIdentity(String id) {
    assertThatThrownBy(() -> IdentityResolver.resolve(db.jdbc, id))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allFixtureIdentitiesAreResolvedServerSide() {
    assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM staff", Integer.class)).isEqualTo(6);
    for (String tenant : List.of("cb", "hs")) {
      assertThat(db.caller(tenant + "-case-worker").role()).isEqualTo(Role.CASE_WORKER);
      assertThat(db.caller(tenant + "-program-manager").nonprofitId()).isEqualTo(tenant);
      assertThat(db.caller(tenant + "-finance").role()).isEqualTo(Role.FINANCE);
    }
  }

  @Test
  void databaseEnforcesTenantAndLifecycleConstraints() {
    String id = db.create("constraints", 1);
    var before = db.snapshot();
    assertThatThrownBy(
            () ->
                db.jdbc.update(
                    "UPDATE assistance_request SET participant_id='hs-p1' WHERE id=?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> db.jdbc.update("UPDATE assistance_request SET status='Paid' WHERE id=?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> db.jdbc.update("UPDATE assistance_request SET amount_cents=0 WHERE id=?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(db.snapshot()).isEqualTo(before);
  }

  @Test
  void rollbackAfterActualWriteRestoresStateAndFunding() {
    String id = db.create("rollback", 30000);
    var before = db.snapshot();
    var balance = summary();
    var failing =
        new JdbcTemplate(db.ds) {
          @Override
          public int update(String sql, Object... args) {
            int result = super.update(sql, args);
            if (sql.startsWith("UPDATE assistance_request"))
              throw new IllegalStateException("Injected after write");
            return result;
          }
        };
    var service = new RequestService(failing, db.tx);
    assertThatThrownBy(() -> service.approve(db.manager, id)).hasMessage("Injected after write");
    assertThat(db.snapshot()).isEqualTo(before);
    assertThat(summary()).isEqualTo(balance);
  }

  @Test
  void competingApprovalsBlockOnRealFundLockAndCannotOverspend() throws Exception {
    db.jdbc.update("UPDATE fund SET allocation_cents=50000 WHERE id='cb-emergency'");
    String a = db.create("a", 30000), b = db.create("b", 30000);
    var attempted = new CountDownLatch(2);
    var observingJdbc =
        new JdbcTemplate(db.ds) {
          @Override
          public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            if (sql.startsWith("SELECT id FROM fund") && sql.endsWith("FOR UPDATE"))
              attempted.countDown();
            return super.queryForObject(sql, type, args);
          }
        };
    var service = new RequestService(observingJdbc, db.tx);
    try (var holder = db.ds.getConnection();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      holder.setAutoCommit(false);
      try (var q =
          holder.prepareStatement("SELECT id FROM fund WHERE id='cb-emergency' FOR UPDATE")) {
        q.executeQuery().close();
      }
      var barrier = new CyclicBarrier(2);
      Callable<String> approveA =
          () -> {
            barrier.await(3, TimeUnit.SECONDS);
            return outcome(() -> service.approve(db.manager, a));
          };
      Callable<String> approveB =
          () -> {
            barrier.await(3, TimeUnit.SECONDS);
            return outcome(() -> service.approve(db.manager, b));
          };
      var fa = workers.submit(approveA);
      var fb = workers.submit(approveB);
      try {
        assertThat(attempted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> fa.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        assertThatThrownBy(() -> fb.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        holder.commit();
      }
      assertThat(List.of(fa.get(5, TimeUnit.SECONDS), fb.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("changed", "INSUFFICIENT_FUNDS");
    }
    assertThat(summary().availableCents()).isEqualTo(20000);
    assertThat(db.service.list(db.cw, "Approved")).hasSize(1);
  }

  @Test
  void concurrentDuplicateCreationAndPaymentsHaveOneEffect() throws Exception {
    var creation =
        race(
            () -> db.service.create(db.cw, db.submission("same", 123)),
            () -> db.service.create(db.cw, db.submission("same", 123)));
    assertThat(creation).extracting(Change::changed).containsExactlyInAnyOrder(true, false);
    assertThat(creation)
        .extracting(x -> x.request().id())
        .containsOnly(creation.getFirst().request().id());
    String id = creation.getFirst().request().id();
    assertThat(
            race(
                () -> db.service.approve(db.manager, id), () -> db.service.approve(db.manager, id)))
        .extracting(Change::changed)
        .containsExactlyInAnyOrder(true, false);
    assertThat(race(() -> db.service.pay(db.finance, id), () -> db.service.pay(db.finance, id)))
        .extracting(Change::changed)
        .containsExactlyInAnyOrder(true, false);
    assertThat(summary().paidCents()).isEqualTo(123);
    assertThat(summary().availableCents()).isEqualTo(999877);
  }

  @Test
  void concurrentApproveVersusRejectHasOneDecision() throws Exception {
    String id = db.create("race", 1);
    var results =
        race(
            () -> outcome(() -> db.service.approve(db.manager, id)),
            () -> outcome(() -> db.service.reject(db.manager, id)));
    assertThat(results).containsExactlyInAnyOrder("changed", "INVALID_STATE");
    assertThat(db.service.get(db.cw, id).status()).isIn("Approved", "Rejected");
  }

  private static String outcome(Callable<Change> action) throws Exception {
    try {
      return action.call().changed() ? "changed" : "unchanged";
    } catch (BusinessException e) {
      return e.code();
    }
  }

  private static <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
    var start = new CyclicBarrier(2);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          workers.submit(
              () -> {
                start.await(3, TimeUnit.SECONDS);
                return a.call();
              });
      var second =
          workers.submit(
              () -> {
                start.await(3, TimeUnit.SECONDS);
                return b.call();
              });
      return List.of(first.get(8, TimeUnit.SECONDS), second.get(8, TimeUnit.SECONDS));
    }
  }

  @Test
  void lockTimeoutIsRetryableAndLeavesPendingUnchanged() throws Exception {
    String id = db.create("busy", 1);
    var before = db.snapshot();
    try (var holder = db.ds.getConnection()) {
      holder.setAutoCommit(false);
      try (var q = holder.createStatement()) {
        q.execute("SELECT * FROM fund WHERE id='cb-emergency' FOR UPDATE");
      }
      try {
        var result =
            McpTools.invoke(
                "approve_request",
                Map.of("requestId", id),
                db.manager,
                db.service,
                db.catalog,
                db.mapper);
        var json = db.mapper.valueToTree(result.structuredContent());
        assertThat(result.isError()).isTrue();
        assertThat(json.at("/error/code").asText()).isEqualTo("BUSY");
        assertThat(json.at("/error/retryable").asBoolean()).isTrue();
      } finally {
        holder.rollback();
      }
    }
    assertThat(db.snapshot()).isEqualTo(before);
  }

  @Test
  void persistentReopenAndExplicitReset() throws Exception {
    String id = db.create("persist", 1);
    db.ds.close();
    try (var reopened = Database.open(db.path)) {
      assertThat(
              new JdbcTemplate(reopened)
                  .queryForObject("SELECT id FROM assistance_request", String.class))
          .isEqualTo(id);
    }
    Database.reset(db.path);
    Database.reset(db.path);
    try (var reopened = Database.open(db.path)) {
      var jdbc = new JdbcTemplate(reopened);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assistance_request", Integer.class))
          .isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff", Integer.class)).isEqualTo(6);
    }
  }
}
