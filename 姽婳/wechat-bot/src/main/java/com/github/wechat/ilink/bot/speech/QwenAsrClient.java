package com.github.wechat.ilink.bot.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 通过千问兼容接口识别 Base64 音频。 */
public final class QwenAsrClient {
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private final AppConfig config;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final OkHttpClient httpClient =
      new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(90)).build();

  public QwenAsrClient(AppConfig config) {
    this.config = config;
  }

  public String transcribe(byte[] audioBytes, String mimeType) throws IOException {
    if (audioBytes == null || audioBytes.length == 0) {
      throw new IllegalArgumentException("audio bytes must not be empty");
    }
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", config.getQwenAsrModel());
    body.put("stream", false);
    ArrayNode content =
        body.putArray("messages").addObject().put("role", "user").putArray("content");
    content
        .addObject()
        .put("type", "input_audio")
        .putObject("input_audio")
        .put(
            "data",
            "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(audioBytes));
    body.putObject("asr_options").put("language", "zh").put("enable_itn", false);

    Request request =
        new Request.Builder()
            .url(config.getDashscopeBaseUrl() + "/chat/completions")
            .header("Authorization", "Bearer " + config.requireDashscopeApiKey())
            .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
            .build();
    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      String responseText = responseBody == null ? "" : responseBody.string();
      if (!response.isSuccessful()) {
        throw new IOException("ASR returned HTTP " + response.code() + ": " + abbreviate(responseText));
      }
      JsonNode contentNode =
          objectMapper.readTree(responseText).path("choices").path(0).path("message").path("content");
      if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
        throw new IOException("ASR response does not contain a transcript");
      }
      return contentNode.asText().trim();
    }
  }

  private static String abbreviate(String value) {
    return value.length() <= 300 ? value : value.substring(0, 300) + "...";
  }
}
