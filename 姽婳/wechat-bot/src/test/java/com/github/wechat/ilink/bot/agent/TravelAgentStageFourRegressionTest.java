package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelKnowledgeChunk;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelAgentStageFourRegressionTest {
  @Test
  void completesRepresentativeCitiesWithoutExternalProviders() throws Exception {
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(message -> message.contains("北京") ? "北京" : ""),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> sampleHits(query.contains("上海") ? "上海" : "苏州"),
            (city, startDate, days) -> sunny(city, startDate, days),
            TravelEvidenceProvider.disabled());
    List<String> goals =
        List.of(
            "2026年8月30日从常州去苏州玩2天，2人预算5000元，喜欢园林和美食",
            "2026年8月30日从常州去上海玩3天，2人预算6500元，喜欢历史和夜景",
            "2026年8月30日从天津去北京玩3天，2人预算7000元，喜欢历史，节奏轻松");

    for (String goal : goals) {
      TravelAgentService.Result result = agent.execute(goal);
      assertTrue(result.completed(), goal);
      assertEquals(result.plan().brief().days(), result.plan().days().size(), goal);
      assertFalse(result.markdownDocument().isBlank(), goal);
      int accounted =
          result.plan().budget().total()
              + result.plan().budget().remaining()
              + result.plan().mapData().referenceRoundTripCost(result.plan().brief().travelers());
      assertTrue(accounted <= result.plan().brief().budgetYuan(), goal);
      assertTrue(
          result.plan().reviewIssues().stream()
              .noneMatch(issue -> issue.severity() == TravelPlan.Severity.ERROR),
          goal);
    }
  }

  private static List<InMemoryTravelVectorStore.Hit> sampleHits(String city) {
    return List.of(
        hit(city, "a", city + "博物馆", false),
        hit(city, "b", city + "历史街区", true),
        hit(city, "c", city + "城市公园", true),
        hit(city, "d", city + "文化中心", false),
        hit(city, "e", city + "夜景地标", true),
        hit(city, "f", city + "艺术馆", false));
  }

  private static InMemoryTravelVectorStore.Hit hit(
      String city, String id, String title, boolean outdoor) {
    String text = title + "位于" + city + "，适合旅行" + (outdoor ? "，属于户外景点" : "，属于室内场馆");
    return new InMemoryTravelVectorStore.Hit(
        new TravelKnowledgeChunk(id, city, "attraction", title, text, "demo", List.of()),
        0.9);
  }

  private static TravelForecast sunny(String city, LocalDate startDate, int days) {
    return new TravelForecast(
        city,
        startDate,
        java.util.stream.IntStream.range(0, days)
            .mapToObj(
                index ->
                    new TravelForecast.Daily(
                        startDate.plusDays(index), true, "晴", 20, 30, 0, 10))
            .toList());
  }
}
