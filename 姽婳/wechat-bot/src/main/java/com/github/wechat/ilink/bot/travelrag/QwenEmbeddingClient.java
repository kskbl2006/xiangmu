package com.github.wechat.ilink.bot.travelrag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 百炼文本向量兼容接口的轻量客户端。 */
public final class QwenEmbeddingClient {
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final int MAX_BATCH_SIZE = 20;

  private final AppConfig config;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String endpoint;
  private final String apiKeyOverride;

  public QwenEmbeddingClient(AppConfig config) {
    this(
        config,
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(90))
            .writeTimeout(Duration.ofSeconds(90))
            .callTimeout(Duration.ofSeconds(90))
            .build(),
        new ObjectMapper(),
        config.getDashscopeBaseUrl() + "/embeddings",
        null);
  }

  QwenEmbeddingClient(
      AppConfig config,
      OkHttpClient httpClient,
      ObjectMapper objectMapper,
      String endpoint,
      String apiKeyOverride) {
    this.config = config;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.endpoint = endpoint;
    this.apiKeyOverride = apiKeyOverride;
  }

  public List<Double> embed(String text) throws IOException {
    return embedAll(List.of(text)).getFirst();
  }

  public List<List<Double>> embedAll(List<String> texts) throws IOException {
    if (texts == null || texts.isEmpty()) {
      throw new IllegalArgumentException("embedding input must not be empty");
    }
    List<List<Double>> vectors = new ArrayList<>();
    for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
      int end = Math.min(texts.size(), start + MAX_BATCH_SIZE);
      vectors.addAll(embedBatch(texts.subList(start, end)));
    }
    return List.copyOf(vectors);
  }

  private List<List<Double>> embedBatch(List<String> texts) throws IOException {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", config.getQwenEmbeddingModel());
    body.put("dimensions", config.getQwenEmbeddingDimension());
    ArrayNode input = body.putArray("input");
    for (String text : texts) {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("embedding text must not be blank");
      }
      input.add(text);
    }

    Request request =
        new Request.Builder()
            .url(endpoint)
            .header(
                "Authorization",
                "Bearer "
                    + (apiKeyOverride == null
                        ? config.requireDashscopeApiKey()
                        : apiKeyOverride))
            .header("Content-Type", "application/json")
            .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      String responseText = responseBody == null ? "" : responseBody.string();
      if (!response.isSuccessful()) {
        throw new IOException(
            "DashScope embedding returned HTTP "
                + response.code()
                + ": "
                + abbreviate(responseText));
      }
      JsonNode data = objectMapper.readTree(responseText).path("data");
      if (!data.isArray() || data.size() != texts.size()) {
        throw new IOException("DashScope embedding response count does not match request");
      }
      List<IndexedVector> indexed = new ArrayList<>();
      for (JsonNode item : data) {
        JsonNode embedding = item.path("embedding");
        if (!embedding.isArray()) {
          throw new IOException("DashScope embedding response contains no vector");
        }
        List<Double> vector = new ArrayList<>();
        for (JsonNode value : embedding) {
          if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IOException("DashScope embedding response contains a non-finite value");
          }
          vector.add(value.asDouble());
        }
        if (vector.size() != config.getQwenEmbeddingDimension()) {
          throw new IOException("Unexpected embedding dimension: " + vector.size());
        }
        indexed.add(new IndexedVector(item.path("index").asInt(indexed.size()), List.copyOf(vector)));
      }
      indexed.sort(Comparator.comparingInt(IndexedVector::index));
      return indexed.stream().map(IndexedVector::vector).toList();
    }
  }

  private static String abbreviate(String value) {
    return value.length() <= 300 ? value : value.substring(0, 300) + "...";
  }

  private record IndexedVector(int index, List<Double> vector) {}
}
