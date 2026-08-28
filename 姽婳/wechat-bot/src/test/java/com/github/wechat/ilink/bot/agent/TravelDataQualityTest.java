package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TravelDataQualityTest {
  @Test
  void gradesCompleteEvidenceHighAndMissingEvidenceLow() {
    TravelBrief brief =
        new TravelBrief(
            "常州", "上海", 1, 2, 3000, "balanced", List.of("历史"), List.of(),
            LocalDate.of(2026, 8, 30), true);
    List<TravelPlan.Activity> activities =
        List.of(
            new TravelPlan.Activity("上午", "甲", "a", false, ""),
            new TravelPlan.Activity("下午", "乙", "b", false, ""));
    TravelMapData completeMap =
        new TravelMapData(
            true,
            "常州",
            "上海",
            List.of(route(brief.startDate(), "G1")),
            List.of(route(brief.startDate(), "G2")),
            Map.of("甲", poi("甲", 31.1), "乙", poi("乙", 31.2)),
            List.of());
    TravelPlan complete =
        new TravelPlan(
            brief,
            new TravelForecast(
                "上海",
                brief.startDate(),
                List.of(new TravelForecast.Daily(brief.startDate(), true, "晴", 20, 30, 0, 8))),
            completeMap,
            List.of(new TravelPlan.DayPlan(1, "城市", "就近用餐", activities)),
            List.of(new TravelPlan.TravelLeg(1, "甲", "乙", "公共交通", 2, 15, 6, "test", 0.8)),
            new TravelPlan.Budget(300, 300, 50, 0, 100, 500),
            "test",
            List.of(),
            0,
            List.of());
    TravelDataQuality.Report high =
        TravelDataQuality.assess(
            complete,
            List.of(),
            new TravelPlanReviewSkill.ReviewResult(true, List.of()));

    TravelPlan missing =
        new TravelPlan(
            brief,
            TravelForecast.unavailable("上海", brief.startDate(), 1),
            List.of(new TravelPlan.DayPlan(1, activities)),
            new TravelPlan.Budget(0, 0, 0, 0, 0),
            "test",
            List.of(),
            0,
            List.of());
    TravelDataQuality.Report low =
        TravelDataQuality.assess(
            missing,
            List.of(),
            new TravelPlanReviewSkill.ReviewResult(false, List.of()));

    assertEquals(TravelDataQuality.Grade.HIGH, high.grade());
    assertTrue(high.score() >= 80);
    assertEquals(TravelDataQuality.Grade.LOW, low.grade());
    assertTrue(low.score() < 55);
  }

  private static TravelMapData.RouteOption route(LocalDate date, String name) {
    return new TravelMapData.RouteOption(
        date, "火车", name, "常州站", "上海站", "08:00", "09:00", 60, 100,
        "", "test", Instant.EPOCH, 0.9);
  }

  private static TravelMapData.PoiSnapshot poi(String name, double latitude) {
    return new TravelMapData.PoiSnapshot(
        name, "上海", latitude, 121.5, 4.5, 0, "09:00-17:00", "", "", Instant.EPOCH);
  }
}
