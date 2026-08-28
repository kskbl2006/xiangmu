package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItinerarySchedulerTest {
  @Test
  void assignsIndoorPlanToRainAndConstrainsArrivalAndReturnDays() {
    TravelBrief brief = brief(3, "balanced", List.of());
    TravelPlan plan =
        plan(
            brief,
            new TravelForecast(
                "北京",
                brief.startDate(),
                List.of(
                    weather(brief.startDate(), true),
                    weather(brief.startDate().plusDays(1), false),
                    weather(brief.startDate().plusDays(2), false))),
            List.of(
                day(1, activity("户外甲", true), activity("户外乙", true)),
                day(2, activity("室内甲", false), activity("室内乙", false)),
                day(3, activity("混合甲", true), activity("混合乙", false))));
    TravelMapData map =
        new TravelMapData(
            true,
            "常州",
            "北京",
            List.of(route(brief.startDate(), "G1", "08:00", "15:00", 300)),
            List.of(route(brief.startDate().plusDays(2), "G2", "09:00", "14:00", 300)),
            Map.of(),
            List.of());

    TravelPlan scheduled = new ItineraryScheduler().schedule(plan, map);

    assertEquals(1, scheduled.days().getFirst().activities().size());
    assertFalse(scheduled.days().getFirst().activities().getFirst().outdoor());
    assertTrue(scheduled.days().getLast().activities().isEmpty());
  }

  @Test
  void clustersNearbyPlacesIntoTheSameDay() {
    TravelBrief brief = brief(2, "balanced", List.of());
    TravelPlan plan =
        plan(
            brief,
            TravelForecast.unavailable("北京", brief.startDate(), 2),
            List.of(
                day(1, activity("A", false), activity("C", false)),
                day(2, activity("B", false), activity("D", false))));
    TravelMapData map =
        new TravelMapData(
            true,
            "常州",
            "北京",
            List.of(),
            List.of(),
            Map.of(
                "A", poi("A", 31.000, 121.000),
                "B", poi("B", 31.001, 121.001),
                "C", poi("C", 32.000, 122.000),
                "D", poi("D", 32.001, 122.001)),
            List.of());

    TravelPlan scheduled = new ItineraryScheduler().schedule(plan, map);

    assertEquals(List.of("A", "B"), scheduled.days().getFirst().activities().stream().map(TravelPlan.Activity::title).toList());
    assertEquals(List.of("C", "D"), scheduled.days().getLast().activities().stream().map(TravelPlan.Activity::title).toList());
  }

  @Test
  void selectsBalancedRailCombinationInsteadOfBlindlyChoosingLowestPrice() {
    LocalDate date = LocalDate.of(2026, 8, 30);
    TravelMapData map =
        new TravelMapData(
            true,
            "常州",
            "北京",
            List.of(
                route(date, "慢车", "12:00", "22:00", 100),
                route(date, "推荐车", "06:00", "10:00", 200)),
            List.of(route(date.plusDays(2), "返程", "18:00", "22:00", 180)),
            Map.of(),
            List.of());

    assertEquals("推荐车", map.recommendedOutbound().orElseThrow().serviceName());
    assertEquals(760, map.referenceRoundTripCost(2));
  }

  private static TravelBrief brief(int days, String pace, List<String> assumptions) {
    return new TravelBrief(
        "常州", "北京", days, 2, 5000, pace, List.of("历史"), assumptions,
        LocalDate.of(2026, 8, 30), true);
  }

  private static TravelPlan plan(
      TravelBrief brief, TravelForecast forecast, List<TravelPlan.DayPlan> days) {
    return new TravelPlan(
        brief,
        forecast,
        days,
        new TravelPlan.Budget(0, 0, 0, 0, 0),
        "test",
        List.of(),
        0,
        List.of());
  }

  private static TravelPlan.DayPlan day(int day, TravelPlan.Activity... activities) {
    return new TravelPlan.DayPlan(day, "主题", "就近用餐", List.of(activities));
  }

  private static TravelPlan.Activity activity(String title, boolean outdoor) {
    return new TravelPlan.Activity("待排程", title, title, outdoor, "推荐理由");
  }

  private static TravelForecast.Daily weather(LocalDate date, boolean rainy) {
    return new TravelForecast.Daily(date, true, rainy ? "中雨" : "晴", 20, 28, rainy ? 90 : 0, 10);
  }

  private static TravelMapData.RouteOption route(
      LocalDate date, String name, String departure, String arrival, double price) {
    int duration =
        Math.max(1, TravelMapData.parseMinutes(arrival) - TravelMapData.parseMinutes(departure));
    return new TravelMapData.RouteOption(
        date, "火车", name, "起点站", "终点站", departure, arrival, duration, price,
        "https://www.12306.cn", "test", Instant.EPOCH, 0.9);
  }

  private static TravelMapData.PoiSnapshot poi(
      String name, double latitude, double longitude) {
    return new TravelMapData.PoiSnapshot(
        name, "北京市", latitude, longitude, 4.5, 0, "09:00-17:00", "", "", Instant.EPOCH);
  }
}
