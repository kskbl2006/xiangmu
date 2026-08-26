package com.github.wechat.ilink.bot.travelrag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class QwenEmbeddingClientTest {
  @Test
  void sendsConfiguredModelAndParsesOrderedVectors() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      ObjectMapper mapper = new ObjectMapper();
      ObjectNode response = mapper.createObjectNode();
      ArrayNode data = response.putArray("data");
      data.addObject().put("index", 1).set("embedding", vector(mapper, 0.0, 1.0));
      data.addObject().put("index", 0).set("embedding", vector(mapper, 1.0, 0.0));
      response.put("model", "qwen3.7-text-embedding");
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(mapper.writeValueAsString(response)));
      QwenEmbeddingClient client =
          new QwenEmbeddingClient(
              AppConfig.fromEnvironment(),
              new OkHttpClient(),
              mapper,
              server.url("/embeddings").toString(),
              "test-key");

      List<List<Double>> vectors = client.embedAll(List.of("first", "second"));
      assertEquals(2, vectors.size());
      assertEquals(256, vectors.getFirst().size());
      assertEquals(1.0, vectors.getFirst().getFirst());
      assertEquals(1.0, vectors.get(1).get(1));

      var recorded = server.takeRequest();
      JsonNode request = mapper.readTree(recorded.getBody().readUtf8());
      assertEquals("qwen3.7-text-embedding", request.path("model").asText());
      assertEquals(256, request.path("dimensions").asInt());
      assertEquals(2, request.path("input").size());
      assertTrue(recorded.getHeader("Authorization").startsWith("Bearer "));
    }
  }

  private static ArrayNode vector(ObjectMapper mapper, double first, double second) {
    ArrayNode vector = mapper.createArrayNode();
    vector.add(first);
    vector.add(second);
    while (vector.size() < 256) vector.add(0.0);
    return vector;
  }

  @Test
  void bundledIndexUsesConfiguredModelAndDimension() {
    InMemoryTravelVectorStore store =
        InMemoryTravelVectorStore.fromResource(
            new ObjectMapper(), "/travel-rag/travel-rag-index.json");
    assertEquals("qwen3.7-text-embedding", store.model());
    assertEquals(256, store.dimension());
    assertEquals(81, store.size());
  }
}
