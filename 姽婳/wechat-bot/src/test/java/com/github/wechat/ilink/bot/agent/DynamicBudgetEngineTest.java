package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DynamicBudgetEngineTest {
  private final DynamicBudgetEngine engine = new DynamicBudgetEngine();

  @Test
  void transportCostReducesDestinationSpendingCapacityWithoutForcingFullSpend() {
    TravelBrief brief =
        new TravelBrief(
            "常州", "北京", 3, 2, 5000, "balanced", List.of("历史"), List.of(),
            LocalDate.of(2026, 9, 1), true);
    TravelPlan plan = plan(brief);
    TravelMapData lowTransport = map(brief, 50);
    TravelMapData highTransport = map(brief, 500);

    TravelPlan.Budget near = engine.calculate(brief, plan, lowTransport);
    TravelPlan.Budget far = engine.calculate(brief, plan, highTransport);

    assertTrue(near.remaining() > far.remaining());
    assertEquals(5000, near.total() + near.remaining() + lowTransport.referenceRoundTripCost(2));
    assertEquals(5000, far.total() + far.remaining() + highTransport.referenceRoundTripCost(2));
    assertTrue(near.total() < 5000);
  }

  @Test
  void selectedPoiPricesBecomeTicketCost() {
    TravelBrief brief =
        new TravelBrief(
            "常州", "苏州", 1, 2, 1200, "balanced", List.of("园林"), List.of(),
            LocalDate.of(2026, 9, 1), true);
    TravelPlan plan = plan(brief);
    TravelMapData.PoiSnapshot poi =
        new TravelMapData.PoiSnapshot(
            "故宫", "地址", 1, 1, 4.8, 60, "08:00-17:00", "", "", null);
    TravelMapData map =
        new TravelMapData(true, "常州", "苏州", List.of(), List.of(), Map.of("故宫", poi), List.of());

    TravelPlan.Budget budget = engine.calculate(brief, plan, map);
    assertEquals(120, budget.tickets());
  }

  private static TravelPlan plan(TravelBrief brief) {
    return new TravelPlan(
        brief,
        TravelForecast.unavailable(brief.destination(), brief.startDate(), brief.days()),
        List.of(
            new TravelPlan.DayPlan(
                1,
                "历史",
                "就近用餐",
                List.of(new TravelPlan.Activity("上午", "故宫", "a", false, "参观")))),
        new TravelPlan.Budget(0, 0, 0, 0, 0),
        "test",
        List.of(),
        0,
        List.of());
  }

  private static TravelMapData map(TravelBrief brief, double oneWayFare) {
    TravelMapData.RouteOption outbound =
        new TravelMapData.RouteOption(
            brief.startDate(), "火车", "G1", "起点", "终点", "08:00", "09:00", 60,
            oneWayFare, "");
    TravelMapData.RouteOption inbound =
        new TravelMapData.RouteOption(
            brief.startDate().plusDays(brief.days() - 1L), "火车", "G2", "终点", "起点",
            "18:00", "19:00", 60, oneWayFare, "");
    return new TravelMapData(
        true, brief.origin(), brief.destination(), List.of(outbound), List.of(inbound), Map.of(), List.of());
  }
}
