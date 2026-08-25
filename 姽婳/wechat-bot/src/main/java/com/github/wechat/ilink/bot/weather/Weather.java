package com.github.wechat.ilink.bot.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Retrieves current conditions for Chinese cities from Seniverse. */
public final class Weather {
  private static final Pattern CITY_PREFIX = Pattern.compile("^(.+?市)");
  private static final Pattern ADMINISTRATIVE_SUFFIX =
      Pattern.compile("(?:特别行政区|自治州|地区|市|区|县)$");
  private static final Object API_RATE_LOCK = new Object();
  private static final long CACHE_MILLIS = Duration.ofMinutes(10).toMillis();
  private static long nextRequestAt;

  record CachedReport(Report report, long expiresAt) {}

  public record Report(String place, String condition, double temperatureC, String lastUpdated) {
    public String toChineseText() {
      String text =
          String.format(Locale.ROOT, "%s当前天气：%s，%.1f℃。", place, condition, temperatureC);
      return lastUpdated == null || lastUpdated.isBlank()
          ? text
          : text + "\n数据更新时间：" + lastUpdated + "。";
    }
  }

  private final AppConfig config;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConcurrentHashMap<String, CachedReport> cache = new ConcurrentHashMap<>();
  private final OkHttpClient httpClient =
      new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build();

  public Weather(AppConfig config) {
    this.config = config;
  }

  public Report current(String location) throws IOException {
    if (location == null || location.isBlank()) {
      throw new IllegalArgumentException("weather location must not be blank");
    }
    String normalized = location.trim();
    CachedReport cached = cache.get(normalized);
    if (cached != null && cached.expiresAt() >= System.currentTimeMillis()) {
      return cached.report();
    }
    if (cached != null) cache.remove(normalized, cached);

    IOException notFound = null;
    for (String candidate : locationCandidates(normalized)) {
      try {
        Report report = queryCurrent(candidate);
        if (cache.size() >= 500) cache.clear();
        cache.put(normalized, new CachedReport(report, System.currentTimeMillis() + CACHE_MILLIS));
        return report;
      } catch (LocationNotFoundException e) {
        notFound = e;
      }
    }
    throw notFound == null
        ? new IOException("未找到中国城市“" + normalized + "”")
        : new IOException("未找到中国城市“" + normalized + "”，请检查城市名称", notFound);
  }

  private Report queryCurrent(String location) throws IOException {
    HttpUrl base = HttpUrl.parse(config.getSeniverseApiBaseUrl() + "/weather/now.json");
    if (base == null) throw new IOException("Invalid SENIVERSE_API_BASE_URL");
    HttpUrl url =
        base.newBuilder()
            .addQueryParameter("key", config.requireSeniverseApiKey())
            .addQueryParameter("location", location)
            .addQueryParameter("language", "zh-Hans")
            .addQueryParameter("unit", "c")
            .build();
    Request request = new Request.Builder().url(url).get().build();

    synchronized (API_RATE_LOCK) {
      waitForRateLimit();
      try (Response response = httpClient.newCall(request).execute()) {
        nextRequestAt = System.currentTimeMillis() + 1_000L;
        ResponseBody responseBody = response.body();
        String body = responseBody == null ? "" : responseBody.string();
        if (!response.isSuccessful()) {
          throw new IOException("心知天气请求失败：HTTP " + response.code());
        }
        JsonNode root = objectMapper.readTree(body);
        if (root.has("status_code")) {
          String code = root.path("status_code").asText();
          if ("AP010010".equals(code)) {
            throw new LocationNotFoundException(location);
          }
          throw new IOException(
              "心知天气请求失败：" + root.path("status").asText("错误码 " + code));
        }
        return parseReport(body, location);
      }
    }
  }

  Report parseReport(String body, String fallbackLocation) throws IOException {
    JsonNode result = objectMapper.readTree(body).path("results").path(0);
    JsonNode location = result.path("location");
    JsonNode now = result.path("now");
    if (location.isMissingNode() || now.isMissingNode()) {
      throw new IOException("心知天气响应缺少 location 或 now");
    }
    String place = compactPath(location.path("path").asText());
    if (place.isBlank()) place = location.path("name").asText(fallbackLocation);
    return new Report(
        place,
        now.path("text").asText("未知"),
        now.path("temperature").asDouble(),
        result.path("last_update").asText());
  }

  static Set<String> locationCandidates(String query) {
    String normalized = query == null ? "" : query.trim().replaceAll("\\s+", "");
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    if (normalized.isBlank()) return candidates;
    Matcher city = CITY_PREFIX.matcher(normalized);
    if (city.find()) {
      candidates.add(city.group(1));
      candidates.add(ADMINISTRATIVE_SUFFIX.matcher(city.group(1)).replaceFirst(""));
      candidates.add(normalized);
    } else {
      candidates.add(normalized);
      candidates.add(ADMINISTRATIVE_SUFFIX.matcher(normalized).replaceFirst(""));
    }
    candidates.remove("");
    return candidates;
  }

  static String compactPath(String path) {
    if (path == null || path.isBlank()) return "";
    LinkedHashSet<String> parts = new LinkedHashSet<>();
    for (String part : path.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isBlank() && !"中国".equals(trimmed)) parts.add(trimmed);
    }
    List<String> ordered = new ArrayList<>(parts);
    Collections.reverse(ordered);
    return String.join(" ", ordered);
  }

  private static void waitForRateLimit() throws IOException {
    long delay = nextRequestAt - System.currentTimeMillis();
    if (delay <= 0) return;
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("心知天气请求被中断", e);
    }
  }

  private static final class LocationNotFoundException extends IOException {
    private LocationNotFoundException(String location) {
      super("心知天气未找到地点“" + location + "”");
    }
  }
}
