package com.github.wechat.ilink.bot.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 计算可解释的数据完整度指标，用于日志和回归检查。 */
public final class TravelDataQuality {
  private TravelDataQuality() {}

  public static Report assess(
      TravelPlan plan,
      List<PlaceCandidate> candidates,
      TravelPlanReviewSkill.ReviewResult review) {
    int weatherTotal = Math.max(1, plan.forecast().days().size());
    int weatherAvailable = (int) plan.forecast().days().stream().filter(TravelForecast.Daily::available).count();

    Set<String> titles = new HashSet<>();
    int expectedLegs = 0;
    for (TravelPlan.DayPlan day : plan.days()) {
      day.activities().forEach(activity -> titles.add(activity.title()));
      expectedLegs += Math.max(0, day.activities().size() - 1);
    }
    int activityTotal = Math.max(1, titles.size());
    int poiMatched = 0;
    int coordinates = 0;
    int openingHours = 0;
    for (String title : titles) {
      TravelMapData.PoiSnapshot poi = plan.mapData().poi(title);
      if (poi == null) continue;
      poiMatched++;
      if (poi.latitude() != 0 && poi.longitude() != 0) coordinates++;
      if (!poi.openingHours().isBlank()) openingHours++;
    }

    int routeDirections =
        (plan.mapData().outboundRoutes().isEmpty() ? 0 : 1)
            + (plan.mapData().returnRoutes().isEmpty() ? 0 : 1);
    int pricedDirections =
        (hasPrice(plan.mapData().outboundRoutes()) ? 1 : 0)
            + (hasPrice(plan.mapData().returnRoutes()) ? 1 : 0);
    double cityLegCoverage =
        expectedLegs == 0 ? 1.0 : Math.min(1.0, plan.cityLegs().size() / (double) expectedLegs);
    double score =
        ratio(weatherAvailable, weatherTotal) * 15
            + ratio(poiMatched, activityTotal) * 15
            + ratio(coordinates, activityTotal) * 15
            + ratio(openingHours, activityTotal) * 10
            + routeDirections / 2.0 * 15
            + pricedDirections / 2.0 * 10
            + cityLegCoverage * 10
            + (review.passed() ? 10 : 0);
    int roundedScore = (int) Math.round(score);
    Grade grade = roundedScore >= 80 ? Grade.HIGH : roundedScore >= 55 ? Grade.MEDIUM : Grade.LOW;
    return new Report(
        roundedScore,
        grade,
        weatherAvailable,
        weatherTotal,
        poiMatched,
        titles.size(),
        coordinates,
        openingHours,
        routeDirections,
        pricedDirections,
        plan.cityLegs().size(),
        expectedLegs,
        candidates == null ? 0 : candidates.size());
  }

  private static boolean hasPrice(List<TravelMapData.RouteOption> routes) {
    return routes.stream().anyMatch(route -> route.referencePriceYuan() > 0);
  }

  private static double ratio(int value, int total) {
    return Math.min(1.0, value / (double) Math.max(1, total));
  }

  public enum Grade {
    HIGH,
    MEDIUM,
    LOW
  }

  public record Report(
      int score,
      Grade grade,
      int weatherAvailable,
      int weatherTotal,
      int poiMatched,
      int activityTotal,
      int coordinatesAvailable,
      int openingHoursAvailable,
      int routeDirections,
      int pricedDirections,
      int cityLegsAvailable,
      int cityLegsExpected,
      int candidatesCollected) {}
}
