package com.github.wechat.ilink.sdk.core.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonSerializer implements Serializer {
  private final ObjectMapper mapper =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          // iLink 网关可能不按 UTF-8 解码请求。
          // JSON Unicode 转义可在保留语义的同时避免中文编码异常。
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
