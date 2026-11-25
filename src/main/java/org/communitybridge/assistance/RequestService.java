package org.communitybridge.assistance;

import static org.communitybridge.assistance.Domain.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant predicates and lock order are part of the business contract (R02, R04-R07). */
public class RequestService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public RequestService(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  public List<Participant> participants(Caller c) {
    return jdbc.query(
        "SELECT id,display_name FROM participant WHERE nonprofit_id=? ORDER BY id",
        (rs, n) -> new Participant(rs.getString(1), rs.getString(2)),
        c.nonprofitId());
  }

  public List<Fund> funds(Caller c) {
    return jdbc.query(
        "SELECT id,name,allocation_cents,currency FROM fund WHERE nonprofit_id=? ORDER BY id",
        (rs, n) -> new Fund(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)),
        c.nonprofitId());
  }

  public Request get(Caller c, String id) {
    var rows =
        jdbc.query(
            "SELECT * FROM assistance_request WHERE nonprofit_id=? AND id=?",
            this::map,
            c.nonprofitId(),
            id);
    if (rows.isEmpty()) throw BusinessException.notFound();
    return rows.getFirst();
  }

  public List<Request> list(Caller c, String status) {
    if (status == null)
      return jdbc.query(
          "SELECT * FROM assistance_request WHERE nonprofit_id=? ORDER BY created_at,id",
          this::map,
          c.nonprofitId());
    return jdbc.query(
        "SELECT * FROM assistance_request WHERE nonprofit_id=? AND status=? ORDER BY created_at,id",
        this::map,
        c.nonprofitId(),
        status);
  }

  public List<FundSummary> summaries(Caller c, String fundId) {
    // One SQL statement reads status and balances from the same statement snapshot.
    var result =
        jdbc.query(
            """
            SELECT f.id,f.name,f.allocation_cents,
             COALESCE(SUM(CASE WHEN r.status='Approved' THEN CAST(r.amount_cents AS DECIMAL(38,0)) ELSE 0 END),0) committed,
             COALESCE(SUM(CASE WHEN r.status='Paid' THEN CAST(r.amount_cents AS DECIMAL(38,0)) ELSE 0 END),0) paid
            FROM fund f LEFT JOIN assistance_request r ON r.nonprofit_id=f.nonprofit_id AND r.fund_id=f.id
            WHERE f.nonprofit_id=?
            """
                + (fundId == null ? "" : " AND f.id=?")
                + " GROUP BY f.id,f.name,f.allocation_cents ORDER BY f.id",
            (rs, n) -> {
              long allocation = rs.getLong(3),
                  committed = rs.getBigDecimal(4).longValueExact(),
                  paid = rs.getBigDecimal(5).longValueExact();
              return new FundSummary(
                  rs.getString(1),
                  rs.getString(2),
                  "USD",
                  allocation,
                  committed,
                  paid,
                  Math.subtractExact(Math.subtractExact(allocation, committed), paid));
            },
            fundId == null
                ? new Object[] {c.nonprofitId()}
                : new Object[] {c.nonprofitId(), fundId});
    if (fundId != null && result.isEmpty()) throw BusinessException.notFound();
    return result;
  }

  public Change create(Caller c, Submission raw) {
    c.require(Role.CASE_WORKER);
    var s =
        new Submission(
            raw.participantId(),
            raw.fundId(),
            raw.purpose().strip(),
            raw.amountCents(),
            raw.clientSubmissionId());
    if (s.amountCents() < 1
        || s.amountCents() > MAX_CENTS
        || s.purpose().isBlank()
        || s.purpose().codePointCount(0, s.purpose().length()) > 500)
      throw new BusinessException("VALIDATION_ERROR", "Invalid amount or purpose.");
    try {
      return tx.execute(
          ignored -> {
            var old = findSubmission(c, s.clientSubmissionId());
            if (!old.isEmpty()) return replay(old.getFirst(), s);
            requireReference("participant", c, s.participantId());
            requireReference("fund", c, s.fundId());
            String id = UUID.randomUUID().toString();
            jdbc.update(
                """
                INSERT INTO assistance_request(id,nonprofit_id,participant_id,fund_id,creator_id,client_submission_id,purpose,amount_cents,status,created_at)
                VALUES(?,?,?,?,?,?,?,?,'Pending',?)
                """,
                id,
                c.nonprofitId(),
                s.participantId(),
                s.fundId(),
                c.id(),
                s.clientSubmissionId(),
                s.purpose(),
                s.amountCents(),
                now());
            return new Change(get(c, id), true);
          });
    } catch (DuplicateKeyException duplicate) {
      // The losing transaction has rolled back before reading the committed winner.
      return tx.execute(
          ignored -> {
            var old = findSubmission(c, s.clientSubmissionId());
            if (old.isEmpty()) throw duplicate;
            return replay(old.getFirst(), s);
          });
    }
  }

  private List<Request> findSubmission(Caller c, String key) {
    return jdbc.query(
        "SELECT * FROM assistance_request WHERE nonprofit_id=? AND creator_id=? AND"
            + " client_submission_id=?",
        this::map,
        c.nonprofitId(),
        c.id(),
        key);
  }

  private Change replay(Request r, Submission s) {
    if (!r.participantId().equals(s.participantId())
        || !r.fundId().equals(s.fundId())
        || !r.purpose().equals(s.purpose())
        || r.amountCents() != s.amountCents())
      throw new BusinessException(
          "IDEMPOTENCY_CONFLICT", "Submission key already has a different payload.");
    return new Change(r, false);
  }

  private void requireReference(String table, Caller c, String id) {
    // table is a private constant chosen only at the two call sites, never client input.
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE nonprofit_id=? AND id=?",
            Integer.class,
            c.nonprofitId(),
            id)
        != 1) throw BusinessException.notFound();
  }

  public Change approve(Caller c, String id) {
    c.require(Role.PROGRAM_MANAGER);
    return transition(c, id, "Approved");
  }

  public Change reject(Caller c, String id) {
    c.require(Role.PROGRAM_MANAGER);
    return transition(c, id, "Rejected");
  }

  public Change pay(Caller c, String id) {
    c.require(Role.FINANCE);
    return transition(c, id, "Paid");
  }

  private Change transition(Caller c, String id, String target) {
    return tx.execute(
        ignored -> {
          Request first =
              get(c, id); // Business fields, including fund, cannot change after submission.
          jdbc.queryForObject(
              "SELECT id FROM fund WHERE nonprofit_id=? AND id=? FOR UPDATE",
              String.class,
              c.nonprofitId(),
              first.fundId());
          var r =
              jdbc.queryForObject(
                  "SELECT * FROM assistance_request WHERE nonprofit_id=? AND id=? FOR UPDATE",
                  this::map,
                  c.nonprofitId(),
                  id);
          if (r.status().equals(target) || (target.equals("Approved") && r.status().equals("Paid")))
            return new Change(r, false);
          String required = target.equals("Paid") ? "Approved" : "Pending";
          if (!r.status().equals(required))
            throw new BusinessException(
                "INVALID_STATE", "Request state does not permit this operation.");
          if (target.equals("Approved")
              && summaries(c, r.fundId()).getFirst().availableCents() < r.amountCents())
            throw new BusinessException("INSUFFICIENT_FUNDS", "Insufficient available funding.");
          if (target.equals("Paid"))
            jdbc.update(
                "UPDATE assistance_request SET status='Paid',paid_by=?,paid_at=? WHERE"
                    + " nonprofit_id=? AND id=?",
                c.id(),
                now(),
                c.nonprofitId(),
                id);
          else
            jdbc.update(
                "UPDATE assistance_request SET status=?,decision_by=?,decision_at=? WHERE"
                    + " nonprofit_id=? AND id=?",
                target,
                c.id(),
                now(),
                c.nonprofitId(),
                id);
          return new Change(get(c, id), true);
        });
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private Request map(ResultSet rs, int n) throws SQLException {
    return new Request(
        rs.getString("id"),
        rs.getString("participant_id"),
        rs.getString("fund_id"),
        rs.getString("purpose"),
        rs.getLong("amount_cents"),
        "USD",
        rs.getString("status"),
        rs.getString("creator_id"),
        rs.getString("client_submission_id"),
        instant(rs, "created_at"),
        rs.getString("decision_by"),
        instant(rs, "decision_at"),
        rs.getString("paid_by"),
        instant(rs, "paid_at"));
  }
}
