package org.communitybridge.assistance;

import java.time.Instant;

public final class Domain {
  private Domain() {}

  public static final long MAX_CENTS = 9_007_199_254_740_991L;

  public enum Role {
    CASE_WORKER,
    PROGRAM_MANAGER,
    FINANCE
  }

  public record Caller(String id, String nonprofitId, Role role) {
    public void require(Role required) {
      if (role != required)
        throw new BusinessException("FORBIDDEN", "Role does not permit this operation.");
    }
  }

  public record Participant(String id, String displayName) {}

  public record Fund(String id, String name, long allocationCents, String currency) {}

  public record Request(
      String id,
      String participantId,
      String fundId,
      String purpose,
      long amountCents,
      String currency,
      String status,
      String creatorId,
      String clientSubmissionId,
      Instant createdAt,
      String decisionBy,
      Instant decisionAt,
      String paidBy,
      Instant paidAt) {}

  public record Change(Request request, boolean changed) {}

  public record FundSummary(
      String fundId,
      String name,
      String currency,
      long allocationCents,
      long committedCents,
      long paidCents,
      long availableCents) {}

  public record Submission(
      String participantId,
      String fundId,
      String purpose,
      long amountCents,
      String clientSubmissionId) {}
}
