package org.communitybridge.assistance;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DurabilityTest {
  @TempDir Path temp;

  @Test
  void acknowledgedApprovalAndPaymentSurviveImmediateProcessHalt() throws Exception {
    Path path = temp.resolve("durable");
    String id;
    try (var fixture = new TestDatabase(path)) {
      id = fixture.create("durability", 30000);
    }
    haltAfter(path, id, "approve");
    try (var ds = Database.open(path)) {
      assertThat(
              new JdbcTemplate(ds)
                  .queryForObject(
                      "SELECT status FROM assistance_request WHERE id=?", String.class, id))
          .isEqualTo("Approved");
    }
    haltAfter(path, id, "pay");
    try (var ds = Database.open(path)) {
      var jdbc = new JdbcTemplate(ds);
      var service =
          new RequestService(jdbc, new TransactionTemplate(new JdbcTransactionManager(ds)));
      var caller = IdentityResolver.resolve(jdbc, "cb-finance");
      assertThat(service.get(caller, id).status()).isEqualTo("Paid");
      var balance = service.summaries(caller, "cb-emergency").getFirst();
      assertThat(balance.committedCents()).isZero();
      assertThat(balance.paidCents()).isEqualTo(30000);
      assertThat(balance.availableCents()).isEqualTo(970000);
    }
  }

  private void haltAfter(Path path, String id, String operation) throws Exception {
    Path output = temp.resolve(operation + ".log");
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                CrashAfterCommit.class.getName(),
                path.toString(),
                id,
                operation)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).as(Files.readString(output)).isZero();
      assertThat(Files.readString(output)).contains("ACKNOWLEDGED");
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  public static class CrashAfterCommit {
    public static void main(String[] args) {
      var ds = Database.open(Path.of(args[0]));
      var jdbc = new JdbcTemplate(ds);
      var service =
          new RequestService(jdbc, new TransactionTemplate(new JdbcTransactionManager(ds)));
      if (args[2].equals("approve"))
        service.approve(IdentityResolver.resolve(jdbc, "cb-program-manager"), args[1]);
      else service.pay(IdentityResolver.resolve(jdbc, "cb-finance"), args[1]);
      System.out.println("ACKNOWLEDGED");
      System.out.flush();
      // Deliberately bypass JDBC close and JVM shutdown hooks, as an abrupt OS termination can.
      Runtime.getRuntime().halt(0);
    }
  }
}
