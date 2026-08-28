package com.github.wechat.ilink.bot.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 同步调用千问语音合成并返回音频数据。 */
public final class QwenTtsClient {
  public record Audio(byte[] bytes, String format) {}

  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private final AppConfig config;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final OkHttpClient httpClient =
      new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(120)).build();

  public QwenTtsClient(AppConfig config) {
    this.config = config;
  }

  public Audio synthesize(String text) throws IOException {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("TTS text must not be blank");
    }
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", config.getQwenTtsModel());
    body.putObject("input")
        .put("text", text)
        .put("voice", config.getQwenTtsVoice())
        .put("language_type", "Chinese");
    Request request =
        new Request.Builder()
            .url(config.getDashscopeNativeBaseUrl() + "/services/aigc/multimodal-generation/generation")
            .header("Authorization", "Bearer " + config.requireDashscopeApiKey())
            .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
            .build();
    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      String responseText = responseBody == null ? "" : responseBody.string();
      if (!response.isSuccessful()) {
        throw new IOException("TTS returned HTTP " + response.code() + ": " + abbreviate(responseText));
      }
      JsonNode audio = objectMapper.readTree(responseText).path("output").path("audio");
      String data = audio.path("data").asText();
      if (!data.isBlank()) {
        return new Audio(Base64.getDecoder().decode(data), "wav");
      }
      String url = audio.path("url").asText();
      if (url.isBlank()) {
        throw new IOException("TTS response does not contain audio data or URL");
      }
      return download(url);
    }
  }

  private Audio download(String url) throws IOException {
    Request request = new Request.Builder().url(url).get().build();
    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody body = response.body();
      if (!response.isSuccessful() || body == null) {
        throw new IOException("Unable to download generated TTS audio; HTTP " + response.code());
      }
      String contentType = response.header("Content-Type", "audio/wav");
      String format = contentType.contains("mpeg") ? "mp3" : "wav";
      return new Audio(body.bytes(), format);
    }
  }

  private static String abbreviate(String value) {
    return value.length() <= 300 ? value : value.substring(0, 300) + "...";
  }
}
