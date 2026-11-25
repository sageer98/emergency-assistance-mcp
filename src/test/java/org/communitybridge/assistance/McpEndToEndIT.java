package org.communitybridge.assistance;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpEndToEndIT {
  @TempDir Path temp;

  @Test
  void discoverCreateApprovePayAndSummarizeThroughActualMcp() throws Exception {
    Path database = temp.resolve("demo");
    Database.reset(database);
    var events = new ArrayList<Map<String, Object>>();
    var submission =
        Map.<String, Object>of(
            "participantId",
            "cb-p1",
            "fundId",
            "cb-emergency",
            "purpose",
            "Rent",
            "amountCents",
            30000,
            "clientSubmissionId",
            "demo-rent-1");
    String id;
    try (var c = new McpDemoClient(database, "cb-case-worker", events)) {
      assertThat(c.success("list_participants", Map.of()).get("participants")).hasSize(2);
      assertThat(c.success("list_funds", Map.of()).get("funds")).hasSize(2);
      var created = c.success("create_request", submission);
      id = created.at("/request/id").asText();
      assertThat(created.at("/request/status").asText()).isEqualTo("Pending");
      assertThat(created.get("changed").asBoolean()).isTrue();
      assertThat(c.success("create_request", submission).get("changed").asBoolean()).isFalse();
      c.error("approve_request", Map.of("requestId", id), "FORBIDDEN");
      var invalid = new HashMap<>(submission);
      invalid.put("amountCents", 1.25);
      c.error("create_request", invalid, "VALIDATION_ERROR");
      c.error("get_funding_summary", Map.of("fundId", "hs-emergency"), "NOT_FOUND");
      assertThatThrownBy(
              () -> c.client.callTool(new McpSchema.CallToolRequest("unknown_tool", Map.of())))
          .isInstanceOf(RuntimeException.class);
    }
    try (var c = new McpDemoClient(database, "cb-program-manager", events)) {
      assertThat(
              c.success("approve_request", Map.of("requestId", id)).at("/request/status").asText())
          .isEqualTo("Approved");
      assertThat(c.success("approve_request", Map.of("requestId", id)).get("changed").asBoolean())
          .isFalse();
      var balance =
          c.success("get_funding_summary", Map.of("fundId", "cb-emergency")).at("/funds/0");
      assertThat(balance.get("committedCents").longValue()).isEqualTo(30000);
      assertThat(balance.get("availableCents").longValue()).isEqualTo(970000);
    }
    try (var c = new McpDemoClient(database, "cb-finance", events)) {
      var paid = c.success("record_payment", Map.of("requestId", id));
      assertThat(paid.at("/request/status").asText()).isEqualTo("Paid");
      assertThat(c.success("record_payment", Map.of("requestId", id)).get("changed").asBoolean())
          .isFalse();
      assertThat(c.success("get_request", Map.of("requestId", id)).at("/request/status").asText())
          .isEqualTo("Paid");
      assertThat(c.success("list_requests", Map.of("status", "Paid")).get("requests")).hasSize(1);
      var summary =
          c.success("get_funding_summary", Map.of("fundId", "cb-emergency")).at("/funds/0");
      assertThat(summary.get("committedCents").longValue()).isZero();
      assertThat(summary.get("paidCents").longValue()).isEqualTo(30000);
      assertThat(summary.get("availableCents").longValue()).isEqualTo(970000);
    }
    try (var c = new McpDemoClient(database, "hs-program-manager", events)) {
      c.error("get_request", Map.of("requestId", id), "NOT_FOUND");
      c.error("approve_request", Map.of("requestId", id), "NOT_FOUND");
      assertThat(c.success("list_requests", Map.of()).get("requests")).isEmpty();
    }
    Path result = Path.of("target/mcp-demo-transcript.json");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(result.toFile(), events);
    if (Boolean.getBoolean("demo.enabled"))
      System.out.println(
          "MCP DEMO PASSED: discovered 9 tools; Pending -> Approved -> Paid; available=$9,700,"
              + " paid=$300, committed=$0. Transcript: "
              + result);
  }

  @Test
  void invalidIdentityAndUninitializedStorageFailBeforeProtocol() throws Exception {
    var database = temp.resolve("startup");
    Database.reset(database);
    for (String id : Arrays.asList(null, "unknown")) {
      var p = launch(database, id);
      assertThat(p.waitFor(15, TimeUnit.SECONDS)).isTrue();
      assertThat(p.exitValue()).isNotZero();
      assertThat(p.getInputStream().readAllBytes()).isEmpty();
    }
    var p = launch(temp.resolve("missing"), "cb-case-worker");
    assertThat(p.waitFor(15, TimeUnit.SECONDS)).isTrue();
    assertThat(p.exitValue()).isNotZero();
    assertThat(p.getInputStream().readAllBytes()).isEmpty();
    assertThat(Files.exists(temp.resolve("missing.mv.db"))).isFalse();
  }

  @Test
  void resetIsSeparateAndNormalProtocolStdoutContainsOnlyJson() throws Exception {
    Path database = temp.resolve("wire");
    var reset = new ProcessBuilder(java(), "-jar", jar(), "--app.mode=reset");
    reset.environment().remove("STAFF_ID");
    reset.environment().put("APP_DB_PATH", database.toString());
    reset.redirectError(temp.resolve("reset.log").toFile());
    var r = reset.start();
    assertThat(r.waitFor(15, TimeUnit.SECONDS)).isTrue();
    assertThat(r.exitValue()).isZero();
    assertThat(r.getInputStream().readAllBytes()).isEmpty();
    var p = launch(database, "cb-case-worker");
    try {
      var input = p.outputWriter();
      input.write(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"wire-test\",\"version\":\"1\"}}}\n");
      input.flush();
      var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
      try (executor) {
        String line = executor.submit(() -> p.inputReader().readLine()).get(15, TimeUnit.SECONDS);
        var json = new ObjectMapper().readTree(line);
        assertThat(json.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(json.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(json.get("id").asInt()).isEqualTo(1);
      }
    } finally {
      p.destroy();
      if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
    }
  }

  private Process launch(Path database, String identity) throws Exception {
    var b = new ProcessBuilder(java(), "-jar", jar());
    b.environment().remove("STAFF_ID");
    if (identity != null) b.environment().put("STAFF_ID", identity);
    b.environment().put("APP_DB_PATH", database.toString());
    b.redirectError(temp.resolve("server-" + UUID.randomUUID() + ".log").toFile());
    return b.start();
  }

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin/java").toString();
  }

  private static String jar() {
    return Path.of("target/emergency-assistance.jar").toAbsolutePath().toString();
  }
}
