package com.github.wechat.ilink.sdk.core.serializer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;

class JsonSerializerTest {
  @Test
  void escapesNonAsciiTextForGatewayCompatibility() {
    String text = "已收到你的消息";
    String json = new JsonSerializer().serialize(Collections.singletonMap("text", text));

    assertFalse(json.contains(text));
    assertTrue(json.contains("\\u"));
  }
}
