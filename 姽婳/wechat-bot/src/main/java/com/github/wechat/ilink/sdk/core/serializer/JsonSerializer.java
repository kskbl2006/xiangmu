package com.github.wechat.ilink.sdk.core.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonSerializer implements Serializer {
  private final ObjectMapper mapper =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          // The iLink gateway may decode request bytes without honoring UTF-8. JSON Unicode escapes
          // keep outbound Chinese text ASCII-only while preserving its semantic value.
          .configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true);

  public String serialize(Object obj) {
    try {
      return mapper.writeValueAsString(obj);
    } catch (Exception e) {
      throw new RuntimeException("serialize failed", e);
    }
  }

  public <T> T deserialize(String text, Class<T> clazz) {
    try {
      return mapper.readValue(text, clazz);
    } catch (Exception e) {
      throw new RuntimeException("deserialize failed", e);
    }
  }
}
