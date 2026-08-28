package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CityRoutePlannerTest {
  @Test
  void createsAConservativeLegBetweenVerifiedPois() {
    TravelBrief brief =
        new TravelBrief(
            "常州", "上海", 1, 2, 2000, "balanced", List.of(), List.of(),
            LocalDate.of(2026, 9, 1), true);
    TravelPlan plan =
        new TravelPlan(
            brief,
            TravelForecast.unavailable("上海", brief.startDate(), 1),
            List.of(
                new TravelPlan.DayPlan(
                    1,
                    "城市",
                    "就近用餐",
                    List.of(
                        new TravelPlan.Activity("上午", "人民广场", "a", false, ""),
                        new TravelPlan.Activity("下午", "外滩", "b", true, "")))),
            new TravelPlan.Budget(0, 0, 0, 0, 0),
            "test",
            List.of(),
            0,
            List.of());
    TravelMapData map =
        new TravelMapData(
            true,
            "常州",
            "上海",
            List.of(),
            List.of(),
            Map.of(
                "人民广场", poi("人民广场", 31.2304, 121.4737),
                "外滩", poi("外滩", 31.2400, 121.4900)),
            List.of());

    List<TravelPlan.TravelLeg> legs = new CityRoutePlanner().plan(plan, map);

    assertEquals(1, legs.size());
    assertEquals("人民广场", legs.getFirst().from());
    assertEquals("外滩", legs.getFirst().to());
    assertTrue(legs.getFirst().durationMinutes() > 0);
    assertEquals("poi-coordinate-estimate", legs.getFirst().source());
  }

  @Test
  void usesLowWalkingAndSelfDrivingStrategies() {
    TravelMapData map =
        new TravelMapData(
            true,
            "常州",
            "上海",
            List.of(),
            List.of(),
            Map.of(
                "甲", poi("甲", 31.2304, 121.4737),
                "乙", poi("乙", 31.2700, 121.5200)),
            List.of());
    TravelBrief relaxed =
        new TravelBrief(
            "常州", "上海", 1, 2, 2000, "relaxed", List.of(), List.of("同行人包含老人"),
            LocalDate.of(2026, 9, 1), true);
    TravelBrief driving =
        new TravelBrief(
            "常州", "上海", 1, 2, 2000, "balanced", List.of(), List.of("交通偏好：自驾"),
            LocalDate.of(2026, 9, 1), true);

    assertEquals("打车或网约车", new CityRoutePlanner().plan(twoPlacePlan(relaxed), map).getFirst().mode());
    assertEquals("自驾", new CityRoutePlanner().plan(twoPlacePlan(driving), map).getFirst().mode());
  }

  private static TravelPlan twoPlacePlan(TravelBrief brief) {
    return new TravelPlan(
        brief,
        TravelForecast.unavailable(brief.destination(), brief.startDate(), 1),
        List.of(
            new TravelPlan.DayPlan(
                1,
                List.of(
                    new TravelPlan.Activity("上午", "甲", "a", false, ""),
                    new TravelPlan.Activity("下午", "乙", "b", false, "")))),
        new TravelPlan.Budget(0, 0, 0, 0, 0),
        "test",
        List.of(),
        0,
        List.of());
  }

  private static TravelMapData.PoiSnapshot poi(String name, double latitude, double longitude) {
    return new TravelMapData.PoiSnapshot(
        name, "上海", latitude, longitude, 4.8, 0, "", "", "", null);
  }
}
