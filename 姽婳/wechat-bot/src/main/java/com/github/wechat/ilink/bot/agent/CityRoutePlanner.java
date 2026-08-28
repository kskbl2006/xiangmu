package com.github.wechat.ilink.bot.agent;

import java.util.ArrayList;
import java.util.List;

/** 根据已验证的 POI 坐标生成保守的景点间路线。 */
public final class CityRoutePlanner {
  public List<TravelPlan.TravelLeg> plan(TravelPlan plan, TravelMapData mapData) {
    List<TravelPlan.TravelLeg> legs = new ArrayList<>();
    for (TravelPlan.DayPlan day : plan.days()) {
      for (int index = 1; index < day.activities().size(); index++) {
        TravelPlan.Activity previous = day.activities().get(index - 1);
        TravelPlan.Activity current = day.activities().get(index);
        TravelMapData.PoiSnapshot from = mapData.poi(previous.title());
        TravelMapData.PoiSnapshot to = mapData.poi(current.title());
        if (!hasCoordinates(from) || !hasCoordinates(to)) continue;
        double directDistance = haversineKm(from.latitude(), from.longitude(), to.latitude(), to.longitude());
        double routeDistance = directDistance * 1.25;
        Strategy strategy = chooseStrategy(plan.brief(), routeDistance);
        int duration = estimatedDuration(strategy, routeDistance);
        int groupCost = estimatedCost(strategy, routeDistance, plan.brief().travelers());
        legs.add(
            new TravelPlan.TravelLeg(
                day.day(),
                previous.title(),
                current.title(),
                strategy.label,
                routeDistance,
                duration,
                groupCost,
                "poi-coordinate-estimate",
                0.55));
      }
    }
    return List.copyOf(legs);
  }

  private static boolean hasCoordinates(TravelMapData.PoiSnapshot poi) {
    return poi != null && poi.latitude() != 0 && poi.longitude() != 0;
  }

  private static int estimatedTransitFare(double distanceKm) {
    if (distanceKm <= 6) return 3;
    if (distanceKm <= 12) return 5;
    return 7;
  }

  private static Strategy chooseStrategy(TravelBrief brief, double distanceKm) {
    String preferences = String.join(" ", brief.interests()) + " " + String.join(" ", brief.assumptions());
    if (preferences.contains("自驾")) return Strategy.DRIVE;
    boolean lowWalking =
        "relaxed".equals(brief.pace())
            || preferences.contains("少走路")
            || preferences.contains("老人")
            || preferences.contains("长辈");
    double walkingLimit = lowWalking ? 0.8 : 1.5;
    if (distanceKm <= walkingLimit) return Strategy.WALK;
    if (lowWalking && distanceKm <= 10) return Strategy.TAXI;
    return Strategy.TRANSIT;
  }

  private static int estimatedDuration(Strategy strategy, double distanceKm) {
    return switch (strategy) {
      case WALK -> (int) Math.ceil(distanceKm / 4.5 * 60);
      case TAXI, DRIVE -> (int) Math.ceil(distanceKm / 28.0 * 60 + 8);
      case TRANSIT -> (int) Math.ceil(distanceKm / 22.0 * 60 + 12);
    };
  }

  private static int estimatedCost(Strategy strategy, double distanceKm, int travelers) {
    return switch (strategy) {
      case WALK -> 0;
      case TRANSIT -> estimatedTransitFare(distanceKm) * Math.max(1, travelers);
      case TAXI ->
          (int) Math.ceil((12 + Math.max(0, distanceKm - 3) * 2.5) * ((travelers + 3) / 4));
      case DRIVE -> (int) Math.ceil(distanceKm * 0.9 + 10);
    };
  }

  private enum Strategy {
    WALK("步行"),
    TRANSIT("公共交通优先"),
    TAXI("打车或网约车"),
    DRIVE("自驾");

    private final String label;

    Strategy(String label) {
      this.label = label;
    }
  }

  private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
    double radius = 6371.0088;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);
    return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }
}
