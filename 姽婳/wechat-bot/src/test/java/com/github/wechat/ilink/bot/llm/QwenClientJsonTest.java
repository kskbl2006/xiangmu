package com.github.wechat.ilink.bot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class QwenClientJsonTest {
  @Test
  void requestsJsonObjectWithTaskSpecificSystemInstruction() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .addHeader("Content-Type", "application/json")
              .setBody(
                  "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"days\\\":[]}\"}}]}"));
      QwenClient client =
          new QwenClient(
              AppConfig.fromEnvironment(),
              new OkHttpClient(),
              server.url("/chat/completions").toString(),
              "test-key");

      assertEquals("{\"days\":[]}", client.chatJson("只输出JSON", "规划行程"));
      JsonNode request =
          new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
      assertEquals("json_object", request.path("response_format").path("type").asText());
      assertEquals("system", request.path("messages").path(0).path("role").asText());
      assertEquals("只输出JSON", request.path("messages").path(0).path("content").asText());
      assertFalse(request.path("enable_thinking").asBoolean());
    }
  }
}
