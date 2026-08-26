package com.github.wechat.ilink.bot.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.agent.TravelMapData.PoiSnapshot;
import com.github.wechat.ilink.bot.agent.TravelMapData.RouteOption;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Minimal server-side client for Baidu geocoding, cross-city transit and Place 2.0. */
public final class BaiduMapClient {
  public record Point(double latitude, double longitude) {
    String baiduCoordinate() {
      return latitude + "," + longitude;
    }
  }

  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final HttpUrl baseUrl;
  private final String apiKey;
  private final long minimumIntervalNanos;
  private final Clock clock;
  private static final long GEOCODE_CACHE_MILLIS = Duration.ofHours(24).toMillis();
  private static final long POI_CACHE_MILLIS = Duration.ofMinutes(30).toMillis();
  private static final int MAX_CACHE_ENTRIES = 500;
  private final ConcurrentHashMap<String, Cached<Optional<Point>>> geocodeCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Cached<Optional<PoiSnapshot>>> poiCache =
      new ConcurrentHashMap<>();
  /** Personal Baidu accounts allow 3 concurrent calls; keep one request in flight for headroom. */
  private final Semaphore requestPermit = new Semaphore(1, true);
  private long lastRequestNanos;

  private record Cached<T>(T value, long expiresAtEpochMillis) {}

  public BaiduMapClient(AppConfig config) {
    this(
        new OkHttpClient(),
        new ObjectMapper(),
        config.getBaiduMapBaseUrl(),
        config.hasBaiduMapApiKey() ? config.requireBaiduMapApiKey() : "",
        config.getBaiduMapMinimumIntervalMillis(),
        Clock.systemUTC());
  }

  BaiduMapClient(
      OkHttpClient httpClient,
      ObjectMapper objectMapper,
      String baseUrl,
      String apiKey,
      int minimumIntervalMillis,
      Clock clock) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.baseUrl = requireBaseUrl(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.minimumIntervalNanos = Math.max(0, minimumIntervalMillis) * 1_000_000L;
    this.clock = clock;
  }

  public boolean isConfigured() {
    return !apiKey.isBlank();
  }

  public Optional<Point> geocode(String city) throws IOException {
    requireConfigured();
    String normalized = required(city, "city");
    Cached<Optional<Point>> cached = geocodeCache.get(normalized);
    long now = clock.instant().toEpochMilli();
    if (cached != null && cached.expiresAtEpochMillis() >= now) return cached.value();
    if (cached != null) geocodeCache.remove(normalized, cached);
    HttpUrl url =
        endpoint("geocoding/v3/")
            .addQueryParameter("address", normalized)
            .addQueryParameter("city", normalized)
            .addQueryParameter("output", "json")
            .addQueryParameter("ak", apiKey)
            .build();
    JsonNode root = getJson(url);
    JsonNode location = root.path("result").path("location");
    Optional<Point> result =
        location.has("lat") && location.has("lng")
            ? Optional.of(new Point(location.path("lat").asDouble(), location.path("lng").asDouble()))
            : Optional.empty();
    if (geocodeCache.size() >= MAX_CACHE_ENTRIES) geocodeCache.clear();
    geocodeCache.put(normalized, new Cached<>(result, now + GEOCODE_CACHE_MILLIS));
    return result;
  }

  public List<RouteOption> transit(Point origin, Point destination, LocalDate date)
      throws IOException {
    requireConfigured();
    HttpUrl url =
        endpoint("direction/v2/transit")
            .addQueryParameter("origin", origin.baiduCoordinate())
            .addQueryParameter("destination", destination.baiduCoordinate())
            .addQueryParameter("departure_date", date.format(DateTimeFormatter.BASIC_ISO_DATE))
            .addQueryParameter("departure_time", "00:00-23:59")
            .addQueryParameter("tactics_intercity", "0")
            .addQueryParameter("trans_type_intercity", "0")
            .addQueryParameter("page_size", "5")
            .addQueryParameter("page_index", "1")
            .addQueryParameter("output", "json")
            .addQueryParameter("ak", apiKey)
            .build();
    try {
      return parseRoutes(getJson(url), date);
    } catch (BaiduApiException e) {
      // Baidu uses status 1001 when no transit plan is available for this route/date.
      // This is an expected empty result, not an authentication or service failure.
      if (e.status() == 1001) return List.of();
      throw e;
    }
  }

  public Optional<PoiSnapshot> searchPoi(String city, String title) throws IOException {
    requireConfigured();
    String cacheKey = required(city, "city") + "\u0000" + required(title, "title");
    Cached<Optional<PoiSnapshot>> cached = poiCache.get(cacheKey);
    long now = clock.instant().toEpochMilli();
    if (cached != null && cached.expiresAtEpochMillis() >= now) return cached.value();
    if (cached != null) poiCache.remove(cacheKey, cached);
    HttpUrl url =
        endpoint("place/v2/search")
            .addQueryParameter("query", title)
            .addQueryParameter("region", city)
            .addQueryParameter("city_limit", "true")
            .addQueryParameter("scope", "2")
            .addQueryParameter("page_size", "5")
            .addQueryParameter("page_num", "0")
            .addQueryParameter("output", "json")
            .addQueryParameter("ak", apiKey)
            .build();
    JsonNode results = getJson(url).path("results");
    JsonNode selected = selectPoi(results, title);
    Optional<PoiSnapshot> result = selected == null ? Optional.empty() : Optional.of(parsePoi(selected));
    if (poiCache.size() >= MAX_CACHE_ENTRIES) poiCache.clear();
    poiCache.put(cacheKey, new Cached<>(result, now + POI_CACHE_MILLIS));
    return result;
  }

  private JsonNode getJson(HttpUrl url) throws IOException {
    acquireRequestPermit();
    try {
      awaitRateLimit();
      Request request = new Request.Builder().url(url).get().build();
      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          throw new IOException("Baidu Map HTTP " + response.code());
        }
        JsonNode root = objectMapper.readTree(response.body().string());
        int status = root.path("status").asInt(-1);
        if (status != 0) {
          String message = root.path("message").asText(root.path("msg").asText("unknown error"));
          throw new BaiduApiException(status, message);
        }
        return root;
      }
    } finally {
      requestPermit.release();
    }
  }

  private static final class BaiduApiException extends IOException {
    private final int status;

    private BaiduApiException(int status, String message) {
      super("Baidu Map API status=" + status + ": " + message);
      this.status = status;
    }

    private int status() {
      return status;
    }
  }

  private void acquireRequestPermit() throws IOException {
    try {
      requestPermit.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for Baidu Map request permit", e);
    }
  }

  private synchronized void awaitRateLimit() throws IOException {
    long now = System.nanoTime();
    long waitNanos = minimumIntervalNanos - (now - lastRequestNanos);
    if (lastRequestNanos != 0 && waitNanos > 0) {
      try {
        long millis = waitNanos / 1_000_000L;
        int nanos = (int) (waitNanos % 1_000_000L);
        Thread.sleep(millis, nanos);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while respecting Baidu Map rate limit", e);
      }
    }
    lastRequestNanos = System.nanoTime();
  }

  private List<RouteOption> parseRoutes(JsonNode root, LocalDate date) {
    JsonNode routes = root.path("result").path("routes");
    if (!routes.isArray()) return List.of();
    List<RouteOption> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode route : routes) {
      List<JsonNode> vehicles = new ArrayList<>();
      collectVehicles(route, vehicles);
      JsonNode vehicle = selectPrimaryIntercityVehicle(vehicles);
      if (vehicle == null) continue;
      JsonNode detail = vehicle.path("detail");
      int type = vehicle.path("type").asInt();
      String name = detail.path("name").asText("");
      String mode = routeMode(type, name);
      if (name.isBlank()) name = mode;
      String departureTime =
          detail
              .path("departure_time")
              .asText(detail.path("start_info").path("start_time").asText(""));
      String arrivalTime =
          detail
              .path("arrive_time")
              .asText(detail.path("end_info").path("end_time").asText(""));
      int durationMinutes = (int) Math.ceil(route.path("duration").asDouble(0) / 60.0);
      if (!departureTime.isBlank()
          && departureTime.equals(arrivalTime)
          && durationMinutes > 0
          && durationMinutes < 23 * 60) {
        departureTime = "";
        arrivalTime = "";
      }
      String signature = mode + '|' + name + '|' + route.path("duration").asLong();
      if (!seen.add(signature)) continue;
      double price = detail.path("price").asDouble(route.path("price").asDouble(0));
      result.add(
          new RouteOption(
              date,
              mode,
              name,
              detail.path("departure_station").asText(""),
              detail.path("arrive_station").asText(""),
              departureTime,
              arrivalTime,
              durationMinutes,
              price,
              httpUrlOnly(detail.path("booking").asText(""))));
      if (result.size() == 3) return List.copyOf(result);
    }
    return List.copyOf(result);
  }

  private static void collectVehicles(JsonNode node, List<JsonNode> result) {
    if (node == null) return;
    if (node.isObject()) {
      JsonNode vehicle = node.get("vehicle_info");
      if (vehicle != null) {
        result.add(vehicle);
      }
      node.elements().forEachRemaining(child -> collectVehicles(child, result));
    } else if (node.isArray()) {
      node.elements().forEachRemaining(child -> collectVehicles(child, result));
    }
  }

  private static JsonNode selectPrimaryIntercityVehicle(List<JsonNode> vehicles) {
    // A route may contain a coach/airport shuttle before its main train segment.
    // Select by travel-mode priority instead of response order so the route label
    // represents the primary intercity leg requested by train-priority tactics.
    for (int preferredType : List.of(1, 2, 6)) {
      for (JsonNode vehicle : vehicles) {
        if (vehicle.path("type").asInt() == preferredType) return vehicle;
      }
    }
    List<JsonNode> coachLegs =
        vehicles.stream()
            .filter(vehicle -> vehicle.path("type").asInt() == 3)
            .filter(
                vehicle ->
                    looksLikeIntercityCoach(
                        vehicle.path("detail").path("name").asText("")))
            .toList();
    // A single coach leg is a useful direct option. Several coach legs form a
    // slow transfer chain; reducing that chain to its first bus creates a false
    // route label, so leave it out and let the document expose the fallback.
    return coachLegs.size() == 1 ? coachLegs.getFirst() : null;
  }

  private static boolean looksLikeIntercityCoach(String name) {
    return name.contains("机场")
        || name.contains("跨城")
        || name.contains("城际")
        || name.contains("客运")
        || name.contains("长途")
        || name.contains("快线");
  }

  private static String routeMode(int type, String name) {
    return switch (type) {
      case 1 -> "火车";
      case 2 -> "飞机";
      case 6 -> "大巴";
      case 3 -> looksLikeIntercityCoach(name) ? "跨城大巴" : "公共交通";
      default -> "公共交通";
    };
  }

  private PoiSnapshot parsePoi(JsonNode poi) {
    JsonNode detail = poi.path("detail_info");
    JsonNode location = poi.path("location");
    return new PoiSnapshot(
        poi.path("name").asText(""),
        poi.path("address").asText(""),
        location.path("lat").asDouble(0),
        location.path("lng").asDouble(0),
        detail.path("overall_rating").asDouble(0),
        detail.path("price").asDouble(0),
        detail.path("shop_hours").asText(""),
        poi.path("telephone").asText(""),
        httpUrlOnly(detail.path("detail_url").asText("")),
        Instant.now(clock));
  }

  private static JsonNode selectPoi(JsonNode results, String title) {
    if (!results.isArray() || results.isEmpty()) return null;
    String expected = compact(title);
    for (JsonNode candidate : results) {
      String actual = compact(candidate.path("name").asText(""));
      if (actual.equals(expected) || actual.contains(expected) || expected.contains(actual)) return candidate;
    }
    return results.get(0);
  }

  private HttpUrl.Builder endpoint(String path) {
    return baseUrl.newBuilder().addPathSegments(path);
  }

  private void requireConfigured() {
    if (!isConfigured()) throw new IllegalStateException("Baidu Map client is not configured");
  }

  private static HttpUrl requireBaseUrl(String value) {
    HttpUrl url = HttpUrl.parse(required(value, "baseUrl"));
    if (url == null) throw new IllegalArgumentException("Invalid Baidu Map base URL");
    return url;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }

  private static String compact(String value) {
    return value == null ? "" : value.replaceAll("[\\s·・（）()—-]", "");
  }

  private static String httpUrlOnly(String value) {
    return value != null && (value.startsWith("https://") || value.startsWith("http://"))
        ? value
        : "";
  }
}
