package com.github.wechat.ilink.bot.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Small helpers for constructing the JSON Schema subset used by bot tools. */
public final class ToolSchemas {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ToolSchemas() {}

  public static ObjectNode requiredString(String name, String description) {
    ObjectNode schema = OBJECT_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    ObjectNode property = schema.putObject("properties").putObject(name);
    property.put("type", "string");
    property.put("description", description);
    property.put("minLength", 1);
    schema.putArray("required").add(name);
    return schema;
  }
}
