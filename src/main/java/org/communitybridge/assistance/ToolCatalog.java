package org.communitybridge.assistance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/** One source of truth for discovery, input validation, and output verification (R08). */
public final class ToolCatalog {
  public record Contract(JsonNode definition, Schema input, Schema output) {}

  private final Map<String, Contract> contracts = new LinkedHashMap<>();
  private final ObjectMapper mapper;

  public ToolCatalog(ObjectMapper mapper) throws IOException {
    this.mapper = mapper;
    var factory = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012());
    try (var in = new ClassPathResource("mcp-tool-contracts.json").getInputStream()) {
      for (var tool : mapper.readTree(in).get("tools"))
        contracts.put(
            tool.get("name").asText(),
            new Contract(
                tool,
                factory.getSchema(tool.get("inputSchema")),
                factory.getSchema(tool.get("outputSchema"))));
    }
  }

  public Map<String, Contract> contracts() {
    return Map.copyOf(contracts);
  }

  public ObjectNode validate(String name, Map<String, Object> arguments) {
    var contract = contracts.get(name);
    if (contract == null) throw new IllegalArgumentException("Unknown tool.");
    JsonNode node = mapper.valueToTree(arguments);
    if (!(node instanceof ObjectNode object)) throw invalid();
    if (object.has("purpose") && object.get("purpose").isTextual())
      object.put("purpose", object.get("purpose").asText().strip());
    if (!contract.input().validate(object).isEmpty()) throw invalid();
    if (object.has("amountCents") && !object.get("amountCents").isIntegralNumber()) throw invalid();
    return object;
  }

  public void verifyOutput(String name, JsonNode result) {
    if (!contracts.get(name).output().validate(result).isEmpty())
      throw new IllegalStateException("Tool output did not satisfy its contract.");
  }

  private static BusinessException invalid() {
    return new BusinessException(
        "VALIDATION_ERROR", "Arguments do not match the tool input schema.");
  }
}
