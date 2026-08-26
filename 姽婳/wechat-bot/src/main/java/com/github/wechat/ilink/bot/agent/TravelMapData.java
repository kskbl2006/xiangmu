package com.github.wechat.ilink.bot.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dynamic route and POI snapshots queried at plan-generation time. */
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

  /** Lowest non-zero outbound plus return fare, multiplied by traveler count. */
  public int referenceRoundTripCost(int travelers) {
    double outbound = minimumPositiveFare(outboundRoutes);
    double inbound = minimumPositiveFare(returnRoutes);
    if (outbound <= 0 || inbound <= 0) return 0;
    return (int) Math.ceil((outbound + inbound) * Math.max(1, travelers));
  }

  private static double minimumPositiveFare(List<RouteOption> routes) {
    return routes.stream()
        .mapToDouble(RouteOption::referencePriceYuan)
        .filter(value -> value > 0)
        .min()
        .orElse(0);
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
      String bookingUrl) {
    public RouteOption {
      mode = blankTo(mode, "公共交通");
      serviceName = blankTo(serviceName, mode);
      departureStation = blankTo(departureStation, "起点");
      arrivalStation = blankTo(arrivalStation, "终点");
      departureTime = blankTo(departureTime, "待确认");
      arrivalTime = blankTo(arrivalTime, "待确认");
      bookingUrl = bookingUrl == null ? "" : bookingUrl.trim();
      durationMinutes = Math.max(0, durationMinutes);
      referencePriceYuan = Math.max(0, referencePriceYuan);
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
