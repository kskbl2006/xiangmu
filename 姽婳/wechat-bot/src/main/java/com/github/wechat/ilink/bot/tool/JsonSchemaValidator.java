package com.github.wechat.ilink.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** 校验当前工具所需的对象、字符串、数字和布尔类型。 */
public final class JsonSchemaValidator {
  private JsonSchemaValidator() {}

  public static void validate(JsonNode schema, JsonNode value) {
    validateAt("$", schema, value);
  }

  private static void validateAt(String path, JsonNode schema, JsonNode value) {
    String type = schema.path("type").asText();
    if (!matchesType(type, value)) {
      throw new IllegalArgumentException(path + " must be " + type);
    }
    if ("object".equals(type)) validateObject(path, schema, value);
    if ("string".equals(type)) validateString(path, schema, value);
    JsonNode allowed = schema.path("enum");
    if (allowed.isArray()) {
      for (JsonNode candidate : allowed) {
        if (candidate.equals(value)) return;
      }
      throw new IllegalArgumentException(path + " is not one of the allowed values");
    }
  }

  private static void validateObject(String path, JsonNode schema, JsonNode value) {
    Set<String> required = new HashSet<>();
    schema.path("required").forEach(node -> required.add(node.asText()));
    for (String field : required) {
      if (!value.has(field) || value.path(field).isNull()) {
        throw new IllegalArgumentException(path + "." + field + " is required");
      }
    }
    JsonNode properties = schema.path("properties");
    Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode fieldSchema = properties.path(field.getKey());
      if (fieldSchema.isMissingNode()) {
        if (!schema.path("additionalProperties").asBoolean(true)) {
          throw new IllegalArgumentException(path + "." + field.getKey() + " is not allowed");
        }
        continue;
      }
      validateAt(path + "." + field.getKey(), fieldSchema, field.getValue());
    }
  }

  private static void validateString(String path, JsonNode schema, JsonNode value) {
    int minLength = schema.path("minLength").asInt(0);
    if (value.asText().length() < minLength) {
      throw new IllegalArgumentException(path + " must have at least " + minLength + " character(s)");
    }
  }

  private static boolean matchesType(String type, JsonNode value) {
    return switch (type) {
      case "object" -> value != null && value.isObject();
      case "array" -> value != null && value.isArray();
      case "string" -> value != null && value.isTextual();
      case "number" -> value != null && value.isNumber();
      case "integer" -> value != null && value.isIntegralNumber();
      case "boolean" -> value != null && value.isBoolean();
      case "" -> true;
      default -> throw new IllegalArgumentException("Unsupported JSON Schema type: " + type);
    };
  }
}
