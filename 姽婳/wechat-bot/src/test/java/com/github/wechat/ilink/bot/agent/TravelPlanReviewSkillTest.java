package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.agent.TravelPlan.Activity;
import com.github.wechat.ilink.bot.agent.TravelPlan.Budget;
import com.github.wechat.ilink.bot.agent.TravelPlan.DayPlan;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TravelPlanReviewSkillTest {
  private final TravelPlanReviewSkill skill = new TravelPlanReviewSkill();

  @Test
  void detectsAndRepairsHardConstraintFailures() {
    TravelBrief brief =
        new TravelBrief("上海", 2, 2, 2000, "balanced", List.of("夜景"), List.of());
    Activity repeated = new Activity("上午", "外滩", "a", true, "开放时间请核验");
    TravelPlan invalid =
        new TravelPlan(
            brief,
            "小雨，20℃",
            List.of(new DayPlan(1, List.of(repeated, repeated))),
            new Budget(1000, 600, 300, 200, 0),
            List.of(),
            0,
            List.of());

    TravelPlanReviewSkill.ReviewResult first = skill.review(invalid);
    assertFalse(first.passed());
    assertTrue(first.issues().stream().anyMatch(issue -> "DAY_COUNT_MISMATCH".equals(issue.code())));
    assertTrue(first.issues().stream().anyMatch(issue -> "DUPLICATE_ACTIVITY".equals(issue.code())));
    assertTrue(first.issues().stream().anyMatch(issue -> "BUDGET_EXCEEDED".equals(issue.code())));

    TravelPlan repaired = skill.repair(invalid, first.issues());
    TravelPlanReviewSkill.ReviewResult repairedReview = skill.review(repaired);
    assertTrue(
        repairedReview.issues().stream()
            .noneMatch(
                issue ->
                    "DAY_COUNT_MISMATCH".equals(issue.code())
                        || "DUPLICATE_ACTIVITY".equals(issue.code())));
    assertTrue(repaired.reviewRounds() == 1);
  }

  @Test
  void reviewsDynamicTransportBudgetAndClosedPoi() {
    TravelBrief brief =
        new TravelBrief("常州", "上海", 1, 2, 700, "balanced", List.of("文化"), List.of(),
            java.time.LocalDate.of(2026, 8, 28), true);
    Activity closed = new Activity("上午", "临时闭馆景点", "a", false, "文化参观");
    TravelMapData.RouteOption outbound =
        new TravelMapData.RouteOption(
            brief.startDate(), "火车", "G1", "常州站", "上海站", "08:00", "09:00", 60, 200, "");
    TravelMapData.RouteOption inbound =
        new TravelMapData.RouteOption(
            brief.startDate(), "火车", "G2", "上海站", "常州站", "18:00", "19:00", 60, 200, "");
    TravelMapData mapData =
        new TravelMapData(
            true,
            "常州",
            "上海",
            List.of(outbound),
            List.of(inbound),
            Map.of(
                closed.title(),
                new TravelMapData.PoiSnapshot(
                    closed.title(), "测试地址", 0, 0, 3.0, 0, "暂停营业", "", "", Instant.now())),
            List.of());
    TravelPlan plan =
        new TravelPlan(
            brief,
            TravelForecast.unavailable("上海", brief.startDate(), 1),
            mapData,
            List.of(new DayPlan(1, "文化", "就近用餐", List.of(closed))),
            new Budget(60, 50, 40, 30, 20),
            "test",
            List.of(),
            0,
            List.of());

    TravelPlanReviewSkill.ReviewResult review = skill.review(plan);
    assertFalse(review.passed());
    assertTrue(review.issues().stream().anyMatch(issue -> "POI_CLOSED".equals(issue.code())));
    assertTrue(
        review.issues().stream().anyMatch(issue -> "TRANSPORT_BUDGET_EXCEEDED".equals(issue.code())));
    TravelPlan repaired = skill.repair(plan, review.issues());
    assertTrue(repaired.days().getFirst().activities().stream()
        .noneMatch(activity -> closed.title().equals(activity.title())));
  }
}
