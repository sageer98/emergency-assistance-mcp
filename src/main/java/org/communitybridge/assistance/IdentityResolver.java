package org.communitybridge.assistance;

import static org.communitybridge.assistance.Domain.*;

import org.springframework.jdbc.core.JdbcTemplate;

public final class IdentityResolver {
  private IdentityResolver() {}

  public static Caller resolve(JdbcTemplate jdbc, String id) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing staff identity.");
    var matches =
        jdbc.query(
            "SELECT id,nonprofit_id,role FROM staff WHERE id=?",
            (rs, n) -> new Caller(rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3))),
            id);
    if (matches.size() != 1) throw new IllegalArgumentException("Unknown staff identity.");
    return matches.getFirst();
  }
}
