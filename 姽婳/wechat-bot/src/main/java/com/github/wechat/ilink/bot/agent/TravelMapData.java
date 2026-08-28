package com.github.wechat.ilink.bot.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 生成方案时查询的动态路线和 POI 快照。 */
public record TravelMapData(
    boolean enabled,
    String origin,
    String destination,
    List<RouteOption> outboundRoutes,
    List<RouteOption> returnRoutes,
    Map<String, PoiSnapshot> poiByTitle,
    List<String> warnings) {
  public TravelMapData {
    origin = origin == null ? "" : origin.trim();
    destination = destination == null ? "" : destination.trim();
    outboundRoutes = outboundRoutes == null ? List.of() : List.copyOf(outboundRoutes);
    returnRoutes = returnRoutes == null ? List.of() : List.copyOf(returnRoutes);
    poiByTitle =
        poiByTitle == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(poiByTitle));
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public static TravelMapData disabled(String destination) {
    return disabled("", destination);
  }

  public static TravelMapData disabled(String origin, String destination) {
    return new TravelMapData(false, origin, destination, List.of(), List.of(), Map.of(), List.of());
  }

  public PoiSnapshot poi(String title) {
    return poiByTitle.get(title);
  }

  public Optional<RouteOption> recommendedOutbound() {
    return selectRoute(outboundRoutes, true);
  }

  public Optional<RouteOption> recommendedReturn() {
    return selectRoute(returnRoutes, false);
  }

  /** 推荐往返组合的总票价，已按人数计算。 */
  public int referenceRoundTripCost(int travelers) {
    double outbound = recommendedOutbound().map(RouteOption::referencePriceYuan).orElse(0.0);
    double inbound = recommendedReturn().map(RouteOption::referencePriceYuan).orElse(0.0);
    if (outbound <= 0 || inbound <= 0) return 0;
    return (int) Math.ceil((outbound + inbound) * Math.max(1, travelers));
  }

  private static Optional<RouteOption> selectRoute(List<RouteOption> routes, boolean outbound) {
    if (routes == null || routes.isEmpty()) return Optional.empty();
    boolean hasPricedRoute = routes.stream().anyMatch(route -> route.referencePriceYuan() > 0);
    List<RouteOption> candidates =
        hasPricedRoute
            ? routes.stream().filter(route -> route.referencePriceYuan() > 0).toList()
            : routes;
    double maxPrice = candidates.stream().mapToDouble(RouteOption::referencePriceYuan).max().orElse(1);
    int maxDuration = candidates.stream().mapToInt(RouteOption::durationMinutes).max().orElse(1);
    return candidates.stream()
        .min(
            java.util.Comparator.comparingDouble(
                route -> {
                  double price =
                      route.referencePriceYuan() > 0
                          ? route.referencePriceYuan() / Math.max(1, maxPrice)
                          : 1;
                  double duration =
                      route.durationMinutes() > 0
                          ? route.durationMinutes() / (double) Math.max(1, maxDuration)
                          : 1;
                  int time = parseMinutes(outbound ? route.arrivalTime() : route.departureTime());
                  double schedule =
                      time < 0 ? 0.5 : outbound ? time / 1_440.0 : (1_440 - time) / 1_440.0;
                  return price * 0.50 + duration * 0.25 + schedule * 0.25;
                }));
  }

  static int parseMinutes(String value) {
    if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) return -1;
    return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3));
  }

  public record RouteOption(
      LocalDate date,
      String mode,
      String serviceName,
      String departureStation,
      String arrivalStation,
      String departureTime,
      String arrivalTime,
      int durationMinutes,
      double referencePriceYuan,
      String bookingUrl,
      String source,
      Instant queriedAt,
      double confidence) {
    public RouteOption {
      mode = blankTo(mode, "公共交通");
      serviceName = blankTo(serviceName, mode);
      departureStation = blankTo(departureStation, "起点");
      arrivalStation = blankTo(arrivalStation, "终点");
      departureTime = blankTo(departureTime, "待确认");
      arrivalTime = blankTo(arrivalTime, "待确认");
      bookingUrl = bookingUrl == null ? "" : bookingUrl.trim();
      source = blankTo(source, "legacy-provider");
      queriedAt = queriedAt == null ? Instant.now() : queriedAt;
      confidence = Math.max(0, Math.min(1, confidence));
      durationMinutes = Math.max(0, durationMinutes);
      referencePriceYuan = Math.max(0, referencePriceYuan);
    }

    public RouteOption(
        LocalDate date,
        String mode,
        String serviceName,
        String departureStation,
        String arrivalStation,
        String departureTime,
        String arrivalTime,
        int durationMinutes,
        double referencePriceYuan,
        String bookingUrl) {
      this(
          date,
          mode,
          serviceName,
          departureStation,
          arrivalStation,
          departureTime,
          arrivalTime,
          durationMinutes,
          referencePriceYuan,
          bookingUrl,
          "legacy-provider",
          Instant.now(),
          0.5);
    }
  }

  public record PoiSnapshot(
      String name,
      String address,
      double latitude,
      double longitude,
      double rating,
      double referencePriceYuan,
      String openingHours,
      String telephone,
      String detailUrl,
      Instant queriedAt) {
    public PoiSnapshot {
      name = blankTo(name, "景点");
      address = address == null ? "" : address.trim();
      openingHours = openingHours == null ? "" : openingHours.trim();
      telephone = telephone == null ? "" : telephone.trim();
      detailUrl = detailUrl == null ? "" : detailUrl.trim();
      rating = Math.max(0, rating);
      referencePriceYuan = Math.max(0, referencePriceYuan);
      queriedAt = queriedAt == null ? Instant.now() : queriedAt;
    }
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
