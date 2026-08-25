package com.github.wechat.ilink.bot.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonSchemaValidatorTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void validatesRequiredStringAndRejectsExtraFields() throws Exception {
    var schema = ToolSchemas.requiredString("location", "中国城市");
    assertDoesNotThrow(
        () -> JsonSchemaValidator.validate(schema, objectMapper.readTree("{\"location\":\"苏州\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> JsonSchemaValidator.validate(schema, objectMapper.readTree("{}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> JsonSchemaValidator.validate(schema, objectMapper.readTree("{\"location\":12}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JsonSchemaValidator.validate(
                schema, objectMapper.readTree("{\"location\":\"苏州\",\"extra\":true}")));
  }
}
