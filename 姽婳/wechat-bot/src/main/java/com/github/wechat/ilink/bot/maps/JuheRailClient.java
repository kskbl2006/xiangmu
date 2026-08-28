package com.github.wechat.ilink.bot.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.agent.TravelMapData.RouteOption;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 聚合数据 817 站到站铁路班次客户端，带低频缓存。 */
public final class JuheRailClient {
  private static final int MAX_CACHE_ENTRIES = 200;
  private static final String BOOKING_URL = "https://www.12306.cn/";

  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final HttpUrl endpoint;
  private final String apiKey;
  private final Clock clock;
  private final long cacheMillis;
  private final DailyQuotaGuard quotaGuard;
  private final ConcurrentHashMap<String, Cached<List<RouteOption>>> cache =
      new ConcurrentHashMap<>();

  private record Cached<T>(T value, long expiresAtEpochMillis) {}
  private record SeatPrice(String name, double price) {}

  public JuheRailClient(AppConfig config) {
    this(
        new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build(),
        new ObjectMapper(),
        config.getJuheRailApiUrl(),
        config.hasJuheRailApiKey() ? config.requireJuheRailApiKey() : "",
        Clock.systemDefaultZone(),
        Duration.ofMinutes(config.getJuheRailCacheMinutes()),
        config.isJuheRailEnabled(),
        config.getJuheRailDailyLimit(),
        Path.of("runtime", "juhe-rail-quota.properties"));
  }

  JuheRailClient(
      OkHttpClient httpClient,
      ObjectMapper objectMapper,
      String endpoint,
      String apiKey,
      Clock clock,
      Duration cacheDuration) {
    this(httpClient, objectMapper, endpoint, apiKey, clock, cacheDuration, true, 1_000, null);
  }

  JuheRailClient(
      OkHttpClient httpClient,
      ObjectMapper objectMapper,
      String endpoint,
      String apiKey,
      Clock clock,
      Duration cacheDuration,
      boolean enabled,
      int dailyLimit,
      Path quotaStateFile) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.endpoint = requireUrl(endpoint);
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.clock = clock;
    this.cacheMillis = Math.max(1, cacheDuration.toMillis());
    this.quotaGuard = new DailyQuotaGuard(enabled, dailyLimit, quotaStateFile, clock);
  }

  public boolean isConfigured() {
    return !apiKey.isBlank();
  }

  public boolean isAvailable() {
    return isConfigured() && quotaGuard.available();
  }

  public boolean isEnabled() {
    return isConfigured() && quotaGuard.status() != DailyQuotaGuard.Status.DISABLED;
  }

  public RailState operationalState() {
    if (!isConfigured()) return RailState.NO_KEY;
    return switch (quotaGuard.status()) {
      case DISABLED -> RailState.DISABLED;
      case AVAILABLE -> RailState.AVAILABLE;
      case DAILY_LIMIT_REACHED -> RailState.DAILY_LIMIT_REACHED;
      case CIRCUIT_OPEN -> RailState.CIRCUIT_OPEN;
    };
  }

  public enum RailState {
    NO_KEY,
    DISABLED,
    AVAILABLE,
    DAILY_LIMIT_REACHED,
    CIRCUIT_OPEN
  }

  DailyQuotaGuard.Status quotaStatus() {
    return quotaGuard.status();
  }

  int requestsUsedToday() {
    return quotaGuard.used();
  }

  /** 日期超出未来 15 天时直接返回空结果，不消耗额度。 */
  public List<RouteOption> query(String origin, String destination, LocalDate date)
      throws IOException {
    if (!isEnabled()) return List.of();
    LocalDate today = LocalDate.now(clock);
    long daysAhead = ChronoUnit.DAYS.between(today, date);
    if (daysAhead < 0 || daysAhead >= 15) return List.of();
    String from = normalizeStation(origin);
    String to = normalizeStation(destination);
    String cacheKey = from + '\u0000' + to + '\u0000' + date;
    long now = clock.instant().toEpochMilli();
    Cached<List<RouteOption>> cached = cache.get(cacheKey);
    if (cached != null && cached.expiresAtEpochMillis() >= now) return cached.value();
    if (cached != null) cache.remove(cacheKey, cached);
    if (!quotaGuard.acquire()) return List.of();

    HttpUrl url =
        endpoint
            .newBuilder()
            .addQueryParameter("key", apiKey)
            .addQueryParameter("search_type", "1")
            .addQueryParameter("departure_station", from)
            .addQueryParameter("arrival_station", to)
            .addQueryParameter("date", date.toString())
            .addQueryParameter("enable_booking", "1")
            .build();
    Request request =
        new Request.Builder()
            .url(url)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .get()
            .build();
    List<RouteOption> result;
    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        if (response.code() == 429) quotaGuard.openCircuit();
        throw new IOException("Juhe Rail HTTP " + response.code());
      }
      JsonNode root = objectMapper.readTree(response.body().string());
      int errorCode = root.path("error_code").asInt(-1);
      if (errorCode != 0) {
        String reason = root.path("reason").asText("unknown");
        if (isQuotaError(errorCode, reason)) quotaGuard.openCircuit();
        throw new IOException(
            "Juhe Rail API error=" + errorCode + ": " + reason);
      }
      result = parseRoutes(root.path("result"), date, clock.instant());
    }
    if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
    cache.put(cacheKey, new Cached<>(result, now + cacheMillis));
    return result;
  }

  private static List<RouteOption> parseRoutes(
      JsonNode result, LocalDate date, Instant queriedAt) {
    if (!result.isArray()) return List.of();
    List<RouteOption> routes = new ArrayList<>();
    for (JsonNode train : result) {
      String trainNo = train.path("train_no").asText("").trim();
      if (trainNo.isBlank()) continue;
      SeatPrice seat = cheapestPositiveSeat(train.path("prices"));
      String service = seat == null ? trainNo : trainNo + "（" + seat.name() + "）";
      routes.add(
          new RouteOption(
              date,
              "火车",
              service,
              train.path("departure_station").asText(""),
              train.path("arrival_station").asText(""),
              train.path("departure_time").asText(""),
              train.path("arrival_time").asText(""),
              parseDurationMinutes(train.path("duration").asText("")),
              seat == null ? 0 : seat.price(),
              BOOKING_URL,
              "juhe-rail-api-817",
              queriedAt,
              0.9));
    }
    return selectDiverseRoutes(routes);
  }

  private static List<RouteOption> selectDiverseRoutes(List<RouteOption> routes) {
    if (routes.size() <= 5) return List.copyOf(routes);
    java.util.LinkedHashSet<RouteOption> selected = new java.util.LinkedHashSet<>();
    routes.stream()
        .filter(route -> route.referencePriceYuan() > 0)
        .min(Comparator.comparingDouble(RouteOption::referencePriceYuan))
        .ifPresent(selected::add);
    routes.stream()
        .filter(route -> route.durationMinutes() > 0)
        .min(Comparator.comparingInt(RouteOption::durationMinutes))
        .ifPresent(selected::add);
    routes.stream()
        .sorted(Comparator.comparing(RouteOption::departureTime))
        .forEach(
            route -> {
              if (selected.size() < 5) selected.add(route);
            });
    return List.copyOf(selected);
  }

  private static SeatPrice cheapestPositiveSeat(JsonNode prices) {
    if (!prices.isArray()) return null;
    SeatPrice selected = null;
    for (JsonNode price : prices) {
      double value = price.path("price").asDouble(0);
      if (value <= 0) continue;
      SeatPrice candidate = new SeatPrice(price.path("seat_name").asText("参考席位"), value);
      if (selected == null || candidate.price() < selected.price()) selected = candidate;
    }
    return selected;
  }

  private static int parseDurationMinutes(String duration) {
    if (duration == null || duration.isBlank()) return 0;
    String[] parts = duration.trim().split(":");
    try {
      if (parts.length == 2) return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
      return Integer.parseInt(duration.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String normalizeStation(String value) {
    String normalized = value == null ? "" : value.trim();
    return normalized.endsWith("市") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  private static HttpUrl requireUrl(String value) {
    HttpUrl url = HttpUrl.parse(value == null ? "" : value.trim());
    if (url == null) throw new IllegalArgumentException("Invalid Juhe Rail API URL");
    return url;
  }

  private static boolean isQuotaError(int errorCode, String reason) {
    String normalized = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
    return errorCode == 10012
        || errorCode == 10013
        || normalized.contains("quota")
        || normalized.contains("次数")
        || normalized.contains("上限")
        || normalized.contains("余额不足")
        || normalized.contains("超限");
  }
}
