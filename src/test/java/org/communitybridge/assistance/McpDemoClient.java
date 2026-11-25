package org.communitybridge.assistance;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Reusable Java SDK demo client: starts a real packaged stdio server for each role. */
final class McpDemoClient implements AutoCloseable {
  final ObjectMapper mapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  final ToolCatalog catalog;
  final McpSyncClient client;
  final StdioClientTransport transport;
  final List<String> stderr = new CopyOnWriteArrayList<>();
  final List<Map<String, Object>> transcript;
  final String identity;

  McpDemoClient(Path database, String identity, List<Map<String, Object>> transcript)
      throws Exception {
    this.identity = identity;
    this.transcript = transcript;
    catalog = new ToolCatalog(mapper);
    var parameters =
        ServerParameters.builder(Path.of(System.getProperty("java.home"), "bin", "java").toString())
            .args("-jar", Path.of("target/emergency-assistance.jar").toAbsolutePath().toString())
            .env(Map.of("STAFF_ID", identity, "APP_DB_PATH", database.toAbsolutePath().toString()))
            .build();
    transport =
        new StdioClientTransport(parameters, new JacksonMcpJsonMapper(mapper)) {
          @Override
          public List<String> protocolVersions() {
            return List.of("2025-06-18");
          }
        };
    transport.setStdErrorHandler(stderr::add);
    client =
        McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(12))
            .initializationTimeout(Duration.ofSeconds(15))
            .build();
    try {
      var init = client.initialize();
      assertThat(init.protocolVersion()).isEqualTo("2025-06-18");
      assertThat(init.capabilities().tools()).isNotNull();
      assertThat(init.capabilities().resources()).isNull();
      assertThat(init.capabilities().prompts()).isNull();
      transcript.add(Map.of("identity", identity, "protocolVersion", init.protocolVersion()));
      var tools = client.listTools().tools();
      assertThat(tools).hasSize(9);
      assertThat(tools)
          .extracting(McpSchema.Tool::name)
          .containsExactlyInAnyOrderElementsOf(catalog.contracts().keySet());
      for (var tool : tools) {
        var expected = catalog.contracts().get(tool.name()).definition();
        assertThat(mapper.<JsonNode>valueToTree(tool.inputSchema()))
            .isEqualTo(expected.get("inputSchema"));
        assertThat(mapper.<JsonNode>valueToTree(tool.outputSchema()))
            .isEqualTo(expected.get("outputSchema"));
      }
      transcript.add(
          Map.of(
              "identity",
              identity,
              "discoveredTools",
              tools.stream().map(McpSchema.Tool::name).sorted().toList()));
    } catch (Throwable failure) {
      close();
      throw new IllegalStateException(
          "MCP initialization failed; server stderr: " + stderr, failure);
    }
  }

  JsonNode call(String name, Map<String, Object> args) {
    var result = client.callTool(new McpSchema.CallToolRequest(name, args));
    JsonNode node = mapper.valueToTree(result.structuredContent());
    catalog.verifyOutput(name, node);
    assertThat(result.isError()).isEqualTo(!node.get("ok").asBoolean());
    assertThat(result.content()).hasSize(1);
    assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
        .isEqualTo(node.toString());
    transcript.add(Map.of("identity", identity, "tool", name, "arguments", args, "result", node));
    return node;
  }

  JsonNode success(String name, Map<String, Object> args) {
    var node = call(name, args);
    assertThat(node.get("ok").asBoolean()).as(node.toPrettyString()).isTrue();
    return node.get("data");
  }

  void error(String name, Map<String, Object> args, String code) {
    var node = call(name, args);
    assertThat(node.get("ok").asBoolean()).isFalse();
    assertThat(node.at("/error/code").asText()).isEqualTo(code);
  }

  @Override
  public void close() {
    client.closeGracefully();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(transport::awaitForExit).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("MCP server did not close cleanly. " + stderr, e);
    }
  }
}
