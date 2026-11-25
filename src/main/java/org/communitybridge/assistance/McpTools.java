package org.communitybridge.assistance;

import static org.communitybridge.assistance.Domain.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

@Configuration
public class McpTools {
  private static final Logger log = LoggerFactory.getLogger(McpTools.class);

  @Bean
  McpServerTransportProviderBase stdioTransport(ObjectMapper mapper) {
    // SDK 0.18.3's stdio default advertises only 2024-11-05. This server requires
    // the 2025-06-18 tool outputSchema/structuredContent contract over the same wire transport.
    return new StdioServerTransportProvider(new JacksonMcpJsonMapper(mapper)) {
      @Override
      public List<String> protocolVersions() {
        return List.of(ProtocolVersions.MCP_2025_06_18);
      }
    };
  }

  @Bean
  ToolCatalog catalog(ObjectMapper mapper) throws IOException {
    return new ToolCatalog(mapper);
  }

  @Bean
  List<McpServerFeatures.SyncToolSpecification> toolSpecifications(
      ObjectMapper mapper, ToolCatalog catalog, RequestService service, Caller caller) {
    var result = new ArrayList<McpServerFeatures.SyncToolSpecification>();
    var mcpMapper = new JacksonMcpJsonMapper(mapper);
    catalog
        .contracts()
        .forEach(
            (name, contract) -> {
              var d = contract.definition();
              var tool =
                  McpSchema.Tool.builder()
                      .name(name)
                      .description(d.get("description").asText())
                      .inputSchema(mcpMapper, d.get("inputSchema").toString())
                      .outputSchema(
                          mapper.convertValue(
                              d.get("outputSchema"), new TypeReference<Map<String, Object>>() {}))
                      .annotations(
                          mapper.convertValue(
                              d.get("annotations"), McpSchema.ToolAnnotations.class))
                      .build();
              result.add(
                  McpServerFeatures.SyncToolSpecification.builder()
                      .tool(tool)
                      .callHandler(
                          (exchange, request) ->
                              invoke(name, request.arguments(), caller, service, catalog, mapper))
                      .build());
            });
    return List.copyOf(result);
  }

  static McpSchema.CallToolResult invoke(
      String name,
      Map<String, Object> arguments,
      Caller caller,
      RequestService service,
      ToolCatalog catalog,
      ObjectMapper mapper) {
    Map<String, Object> envelope;
    boolean failed = false;
    try {
      var a = catalog.validate(name, arguments);
      String requestId = a.path("requestId").asText();
      Object data =
          switch (name) {
            case "list_participants" -> Map.of("participants", service.participants(caller));
            case "list_funds" -> Map.of("funds", service.funds(caller));
            case "get_request" -> Map.of("request", service.get(caller, requestId));
            case "list_requests" ->
                Map.of(
                    "requests",
                    service.list(caller, a.has("status") ? a.get("status").asText() : null));
            case "get_funding_summary" ->
                Map.of(
                    "funds",
                    service.summaries(caller, a.has("fundId") ? a.get("fundId").asText() : null));
            case "create_request" ->
                service.create(
                    caller,
                    new Submission(
                        a.get("participantId").asText(),
                        a.get("fundId").asText(),
                        a.get("purpose").asText(),
                        a.get("amountCents").longValue(),
                        a.get("clientSubmissionId").asText()));
            case "approve_request" -> service.approve(caller, requestId);
            case "reject_request" -> service.reject(caller, requestId);
            case "record_payment" -> service.pay(caller, requestId);
            default -> throw new IllegalArgumentException("Unknown tool.");
          };
      envelope = Map.of("ok", true, "data", data);
    } catch (BusinessException e) {
      failed = true;
      envelope = error(e.code(), e.getMessage(), false);
    } catch (PessimisticLockingFailureException | QueryTimeoutException e) {
      failed = true;
      envelope = error("BUSY", "Database busy; retry the same operation.", true);
    } catch (Exception e) {
      log.error("Tool operation failed: {}", name, e);
      failed = true;
      envelope = error("INTERNAL_ERROR", "Operation failed; inspect server diagnostics.", false);
    }
    var json = mapper.valueToTree(envelope);
    catalog.verifyOutput(name, json);
    return McpSchema.CallToolResult.builder()
        .isError(failed)
        .structuredContent(json)
        .addTextContent(json.toString())
        .build();
  }

  private static Map<String, Object> error(String code, String message, boolean retryable) {
    return Map.of(
        "ok", false, "error", Map.of("code", code, "message", message, "retryable", retryable));
  }
}
