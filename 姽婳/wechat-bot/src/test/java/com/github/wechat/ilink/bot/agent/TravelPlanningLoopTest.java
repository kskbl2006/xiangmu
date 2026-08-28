package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelKnowledgeChunk;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TravelPlanningLoopTest {
  @Test
  void repairsAnOverBudgetPlanAndEvaluatesAgain() {
    TravelBrief brief =
        new TravelBrief(
            "常州", "苏州", 1, 2, 1200, "balanced", List.of("园林"), List.of(),
            LocalDate.of(2026, 9, 1), true);
    List<InMemoryTravelVectorStore.Hit> hits =
        List.of(hit("a", "收费景点"), hit("b", "免费景点"));
    CandidateCollector collector = new CandidateCollector();
    List<PlaceCandidate> candidates = collector.collect(hits);
    TravelMapData.PoiSnapshot paid =
        new TravelMapData.PoiSnapshot(
            "收费景点", "地址", 1, 1, 4.8, 500, "08:00-17:00", "", "", null);
    TravelMapData map =
        new TravelMapData(
            true, "常州", "苏州", List.of(), List.of(), Map.of("收费景点", paid), List.of());
    PlanningContext context = new PlanningContext("测试目标", brief);
    context.evidence(
        TravelForecast.unavailable("苏州", brief.startDate(), 1), hits,
        collector.mergeMapData(candidates, map), map);

    TravelPlanningLoop.Outcome outcome =
        new TravelPlanningLoop(
                new CnTripPlannerSkill(), new TravelPlanReviewSkill(), new DynamicBudgetEngine(), 2)
            .run(context);

    assertTrue(outcome.review().passed());
    assertEquals(1, context.repairRound());
    assertEquals(PlanningContext.Stage.COMPLETED, context.stage());
    assertTrue(context.trace().stream().anyMatch(step -> step.contains("自动修复")));
    assertTrue(
        outcome.plan().days().getFirst().activities().stream()
            .noneMatch(activity -> "收费景点".equals(activity.title())));
  }

  private static InMemoryTravelVectorStore.Hit hit(String id, String title) {
    return new InMemoryTravelVectorStore.Hit(
        new TravelKnowledgeChunk(
            id, "苏州", "attraction", title, title + "位于苏州", "test", List.of()),
        0.9);
  }
}
