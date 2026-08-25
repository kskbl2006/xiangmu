package com.github.wechat.ilink.bot.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void exportsOpenAiDefinitionAndExecutesValidatedArguments() throws Exception {
    ToolRegistry registry =
        new ToolRegistry(objectMapper, List.of(new CalculatorTool(objectMapper)));
    var definition = registry.definitions().get(0);
    assertEquals("function", definition.path("type").asText());
    assertEquals("calculator", definition.path("function").path("name").asText());
    assertEquals(
        "object",
        definition.path("function").path("parameters").path("type").asText());

    String result = registry.execute("calculator", "{\"expression\":\"(4+6)/2\"}");
    assertTrue(result.contains("\"result\":\"5\""));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.execute("calculator", "{\"expression\":\"1+1\",\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class, () -> registry.execute("missing", "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> registry.execute("calculator", "[]"));
  }

  @Test
  void rejectsEmptyAndOversizedToolResults() {
    BotTool emptyTool = fixedResultTool("empty", "");
    BotTool oversizedTool = fixedResultTool("oversized", "x".repeat(16_385));
    ToolRegistry registry = new ToolRegistry(objectMapper, List.of(emptyTool, oversizedTool));

    assertThrows(IllegalStateException.class, () -> registry.execute("empty", "{}"));
    assertThrows(IllegalStateException.class, () -> registry.execute("oversized", "{}"));
  }

  private BotTool fixedResultTool(String name, String result) {
    return new BotTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "test tool";
      }

      @Override
      public ObjectNode parametersSchema() {
        return objectMapper.createObjectNode().put("type", "object");
      }

      @Override
      public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
        return result;
      }
    };
  }
}
