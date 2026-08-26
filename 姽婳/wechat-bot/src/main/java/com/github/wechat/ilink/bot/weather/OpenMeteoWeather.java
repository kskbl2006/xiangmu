package com.github.wechat.ilink.bot.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.agent.TravelForecast;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Open-Meteo provider for trip-date-aligned daily forecasts. */
public final class OpenMeteoWeather {
  private static final int MAX_FORECAST_DAYS = 16;
  private static final long CACHE_MILLIS = Duration.ofMinutes(30).toMillis();
  private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Map<Integer, String> WEATHER_CODES =
      Map.ofEntries(
          Map.entry(0, "晴"), Map.entry(1, "大致晴朗"), Map.entry(2, "多云"), Map.entry(3, "阴"),
          Map.entry(45, "雾"), Map.entry(48, "雾凇"), Map.entry(51, "小毛毛雨"),
          Map.entry(53, "毛毛雨"), Map.entry(55, "浓毛毛雨"), Map.entry(61, "小雨"),
          Map.entry(63, "中雨"), Map.entry(65, "大雨"), Map.entry(66, "冻雨"),
          Map.entry(67, "强冻雨"), Map.entry(71, "小雪"), Map.entry(73, "中雪"),
          Map.entry(75, "大雪"), Map.entry(77, "雪粒"), Map.entry(80, "小阵雨"),
          Map.entry(81, "阵雨"), Map.entry(82, "强阵雨"), Map.entry(85, "小阵雪"),
          Map.entry(86, "阵雪"), Map.entry(95, "雷暴"), Map.entry(96, "雷暴伴冰雹"),
          Map.entry(99, "强雷暴伴冰雹"));

  private record Location(String name, String admin, double latitude, double longitude, int score) {
    String displayName() {
      return admin == null || admin.isBlank() || admin.equals(name) ? name : admin + " " + name;
    }
  }

  private record CachedForecast(TravelForecast forecast, long expiresAt) {}

  private final AppConfig config;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final OkHttpClient httpClient =
      new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build();
  private final ConcurrentHashMap<String, CachedForecast> cache = new ConcurrentHashMap<>();

  public OpenMeteoWeather(AppConfig config) {
    this.config = config;
  }

  public TravelForecast forecast(String city, LocalDate startDate, int days) throws IOException {
    if (city == null || city.isBlank()) throw new IllegalArgumentException("weather city must not be blank");
    if (startDate == null) throw new IllegalArgumentException("trip start date must not be null");
    int normalizedDays = Math.max(1, Math.min(7, days));
    String cacheKey = city.trim() + "|" + startDate + "|" + normalizedDays;
    CachedForecast cached = cache.get(cacheKey);
    if (cached != null && cached.expiresAt() >= System.currentTimeMillis()) return cached.forecast();

    LocalDate today = LocalDate.now(CHINA_ZONE);
    LocalDate forecastEnd = today.plusDays(MAX_FORECAST_DAYS - 1L);
    if (startDate.isAfter(forecastEnd)) {
      return TravelForecast.unavailable(city.trim(), startDate, normalizedDays);
    }

    Location location = geocode(city.trim());
    String body = fetchForecast(location);
    TravelForecast forecast =
        parseForecast(body, location.displayName(), startDate, normalizedDays);
    if (cache.size() >= 500) cache.clear();
    cache.put(cacheKey, new CachedForecast(forecast, System.currentTimeMillis() + CACHE_MILLIS));
    return forecast;
  }

  private Location geocode(String city) throws IOException {
    LinkedHashMap<String, Location> candidates = new LinkedHashMap<>();
    collectLocations(city, city, candidates);
    if (!city.endsWith("市")) collectLocations(city + "市", city, candidates);
    return candidates.values().stream()
        .max(Comparator.comparingInt(Location::score))
        .orElseThrow(() -> new IOException("Open-Meteo未找到中国城市“" + city + "”"));
  }

  private void collectLocations(
      String query, String requestedCity, Map<String, Location> candidates) throws IOException {
    HttpUrl base = HttpUrl.parse(config.getOpenMeteoGeocodingUrl());
    if (base == null) throw new IOException("Invalid OPEN_METEO_GEOCODING_URL");
    HttpUrl url =
        base.newBuilder()
            .addQueryParameter("name", query)
            .addQueryParameter("count", "10")
            .addQueryParameter("language", "zh")
            .addQueryParameter("format", "json")
            .build();
    JsonNode results = objectMapper.readTree(get(url)).path("results");
    if (!results.isArray()) return;
    String normalizedRequest = stripCitySuffix(requestedCity);
    for (JsonNode node : results) {
      if (!"CN".equalsIgnoreCase(node.path("country_code").asText())) continue;
      String name = node.path("name").asText();
      String admin = node.path("admin1").asText();
      String featureCode = node.path("feature_code").asText();
      int score = 100;
      if (stripCitySuffix(name).equals(normalizedRequest)) score += 80;
      if (featureCode.startsWith("PPLC") || featureCode.startsWith("PPLA")) score += 50;
      if (node.path("admin2").asText().contains(normalizedRequest)) score += 30;
      score += Math.min(20, Integer.toString(Math.max(0, node.path("population").asInt())).length() * 2);
      Location candidate =
          new Location(
              name,
              admin,
              node.path("latitude").asDouble(),
              node.path("longitude").asDouble(),
              score);
      candidates.merge(
          candidate.latitude() + "," + candidate.longitude(),
          candidate,
          (left, right) -> left.score() >= right.score() ? left : right);
    }
  }

  private String fetchForecast(Location location) throws IOException {
    HttpUrl base = HttpUrl.parse(config.getOpenMeteoForecastUrl());
    if (base == null) throw new IOException("Invalid OPEN_METEO_FORECAST_URL");
    HttpUrl url =
        base.newBuilder()
            .addQueryParameter("latitude", Double.toString(location.latitude()))
            .addQueryParameter("longitude", Double.toString(location.longitude()))
            .addQueryParameter("timezone", "Asia/Shanghai")
            .addQueryParameter("forecast_days", Integer.toString(MAX_FORECAST_DAYS))
            .addQueryParameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max")
            .build();
    return get(url);
  }

  private String get(HttpUrl url) throws IOException {
    Request request = new Request.Builder().url(url).get().build();
    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      String body = responseBody == null ? "" : responseBody.string();
      if (!response.isSuccessful()) {
        throw new IOException("Open-Meteo请求失败：HTTP " + response.code());
      }
      return body;
    }
  }

  TravelForecast parseForecast(String body, String place, LocalDate startDate, int days)
      throws IOException {
    JsonNode daily = objectMapper.readTree(body).path("daily");
    JsonNode times = daily.path("time");
    if (!times.isArray()) throw new IOException("Open-Meteo响应缺少daily.time");
    Map<LocalDate, TravelForecast.Daily> byDate = new HashMap<>();
    for (int index = 0; index < times.size(); index++) {
      LocalDate date = LocalDate.parse(times.get(index).asText());
      int code = valueAt(daily.path("weather_code"), index).asInt(-1);
      byDate.put(
          date,
          new TravelForecast.Daily(
              date,
              true,
              WEATHER_CODES.getOrDefault(code, "未知天气"),
              valueAt(daily.path("temperature_2m_min"), index).asDouble(),
              valueAt(daily.path("temperature_2m_max"), index).asDouble(),
              valueAt(daily.path("precipitation_probability_max"), index).asInt(),
              valueAt(daily.path("wind_speed_10m_max"), index).asDouble()));
    }
    List<TravelForecast.Daily> aligned = new ArrayList<>();
    for (int index = 0; index < days; index++) {
      LocalDate date = startDate.plusDays(index);
      aligned.add(byDate.getOrDefault(date, TravelForecast.Daily.unavailable(date)));
    }
    return new TravelForecast(place, startDate, aligned);
  }

  private static JsonNode valueAt(JsonNode values, int index) throws IOException {
    if (!values.isArray() || index >= values.size()) {
      throw new IOException("Open-Meteo响应中的逐日字段长度不一致");
    }
    return values.get(index);
  }

  private static String stripCitySuffix(String value) {
    String normalized = value == null ? "" : value.trim();
    return normalized.endsWith("市") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }
}
