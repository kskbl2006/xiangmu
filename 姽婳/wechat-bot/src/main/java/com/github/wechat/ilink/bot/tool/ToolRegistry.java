package com.github.wechat.ilink.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** 注册工具、导出兼容定义、校验参数并执行调用。 */
public final class ToolRegistry {
  static final int MAX_ARGUMENT_LENGTH = 8_192;
  static final int MAX_RESULT_LENGTH = 16_384;

  private final ObjectMapper objectMapper;
  private final Map<String, BotTool> tools = new LinkedHashMap<>();

  public ToolRegistry(ObjectMapper objectMapper, Collection<? extends BotTool> registeredTools) {
    this.objectMapper = objectMapper;
    for (BotTool tool : registeredTools) {
      if (tool == null || tool.name() == null || tool.name().isBlank()) {
        throw new IllegalArgumentException("tool name must not be blank");
      }
      if (tools.putIfAbsent(tool.name(), tool) != null) {
        throw new IllegalArgumentException("duplicate tool name: " + tool.name());
      }
    }
  }

  public ArrayNode definitions() {
    ArrayNode definitions = objectMapper.createArrayNode();
    for (BotTool tool : tools.values()) {
      ObjectNode function = definitions.addObject().put("type", "function").putObject("function");
      function.put("name", tool.name());
      function.put("description", tool.description());
      function.set("parameters", tool.parametersSchema());
    }
    return definitions;
  }

  public String execute(String name, String argumentsJson) throws Exception {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    BotTool tool = tools.get(name);
    if (tool == null) throw new IllegalArgumentException("unknown tool: " + name);
    String normalizedArguments = argumentsJson == null ? "{}" : argumentsJson;
    if (normalizedArguments.length() > MAX_ARGUMENT_LENGTH) {
      throw new IllegalArgumentException("tool arguments exceed " + MAX_ARGUMENT_LENGTH + " characters");
    }
    JsonNode arguments = objectMapper.readTree(normalizedArguments);
    if (arguments == null || !arguments.isObject()) {
      throw new IllegalArgumentException("tool arguments must be a JSON object");
    }
    JsonSchemaValidator.validate(tool.parametersSchema(), arguments);
    String result = tool.execute(arguments);
    if (result == null || result.isBlank()) {
      throw new IllegalStateException("tool returned an empty result: " + name);
    }
    if (result.length() > MAX_RESULT_LENGTH) {
      throw new IllegalStateException("tool result exceeds " + MAX_RESULT_LENGTH + " characters: " + name);
    }
    return result;
  }

  public int size() {
    return tools.size();
  }

  public List<String> names() {
    return List.copyOf(tools.keySet());
  }
}
