package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelKnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TravelAgentServiceTest {
  @Test
  void asksForOriginBeforeGeneratingArtifacts() throws Exception {
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(message -> "西安"),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> List.of(),
            (city, startDate, days) -> sunnyForecast(city, startDate, days));

    TravelAgentService.Result result =
        agent.execute("2026年10月20日去西安玩4天，2人预算5000元");

    assertFalse(result.completed());
    assertEquals(TravelAgentService.MissingInput.ORIGIN, result.missingInput());
    assertTrue(result.reply().contains("从哪个城市出发"));
    assertEquals(null, result.markdownDocument());
  }

  @Test
  void asksForDestinationInsteadOfHallucinatingPreviousCity() throws Exception {
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(message -> "苏州"),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> List.of(),
            (city, startDate, days) -> sunnyForecast(city, startDate, days));

    TravelAgentService.Result result =
        agent.execute("从常州去玩4天，2人预算5000元，喜欢历史和美食");

    assertFalse(result.completed());
    assertEquals(TravelAgentService.MissingInput.DESTINATION, result.missingInput());
    assertTrue(result.reply().contains("目的地"));
    assertEquals(null, result.markdownDocument());
  }

  @Test
  void keepsOriginalDestinationAfterUserSuppliesSupportedOrigin() throws Exception {
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(message -> "西安"),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> List.of(),
            (city, startDate, days) -> TravelForecast.unavailable(city, startDate, days));

    TravelAgentService.Result result =
        agent.execute(
            "从苏州出发；2026年10月20日去西安玩4天，2人预算5000元，喜欢历史和美食");

    assertTrue(result.completed());
    assertEquals("苏州", result.plan().brief().origin());
    assertEquals("西安", result.plan().brief().destination());
    assertTrue(result.markdownDocument().startsWith("# 西安 4 天旅行方案"));
  }

  @Test
  void completesMvpFromOneHighLevelGoal() throws Exception {
    List<InMemoryTravelVectorStore.Hit> hits = attractions("上海", 8);
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> hits.stream().limit(limit).toList(),
            (city, startDate, days) -> sunnyForecast(city, startDate, days));

    TravelAgentService.Result result =
        agent.execute("从常州出发规划上海3天旅行，2人预算5000元，喜欢历史和夜景");

    assertTrue(result.completed());
    assertEquals(3, result.plan().days().size());
    assertTrue(result.plan().budget().total() < 5000);
    assertEquals(
        5000,
        result.plan().budget().total() + result.plan().budget().remaining());
    assertTrue(result.reply().contains("Markdown 与 PDF 附件"));
    assertTrue(result.markdownDocument().startsWith("# 上海 3 天旅行方案"));
    assertTrue(result.markdownDocument().contains("## 预算分配"));
    assertTrue(result.markdownDocument().contains("| **预算余量** |"));
    assertFalse(result.markdownDocument().contains("attraction:test-"));
    assertFalse(result.markdownDocument().contains("Agent 执行过程"));
  }

  @Test
  void usesUngroundedFallbackWithoutQueryingWrongCityKnowledge() throws Exception {
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(message -> "北京"),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> {
              throw new AssertionError("unsupported city must not query local RAG");
            },
            (city, startDate, days) -> sunnyForecast(city, startDate, days));

    TravelAgentService.Result result = agent.execute("从天津出发帮我规划北京三日游");
    assertTrue(result.completed());
    assertEquals("北京", result.plan().brief().destination());
    assertFalse(result.plan().brief().knowledgeCovered());
    assertTrue(result.markdownDocument().contains("暂未收录进本地知识库"));
    assertFalse(result.markdownDocument().contains("RAG 检索"));
  }

  @Test
  void rendersOptionalRouteAndPoiEnrichment() throws Exception {
    List<InMemoryTravelVectorStore.Hit> hits = attractions("上海", 4);
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> hits,
            (city, startDate, days) -> sunnyForecast(city, startDate, days),
            (brief, plan) ->
                new TravelMapData(
                    true,
                    brief.origin(),
                    brief.destination(),
                    List.of(
                        new TravelMapData.RouteOption(
                            brief.startDate(),
                            "火车",
                            "G7001",
                            "常州站",
                            "上海站",
                            "08:00",
                            "09:05",
                            65,
                            149.5,
                            "")),
                    List.of(
                        new TravelMapData.RouteOption(
                            brief.startDate().plusDays(1),
                            "火车",
                            "G7002",
                            "上海站",
                            "常州站",
                            "18:00",
                            "19:05",
                            65,
                            149.5,
                            "")),
                    Map.of(
                        "测试景点1",
                        new TravelMapData.PoiSnapshot(
                            "测试景点1",
                            "人民大道201号",
                            31.23,
                            121.47,
                            4.8,
                            0,
                            "09:00-17:00",
                            "",
                            "https://map.baidu.com/example",
                            Instant.parse("2026-08-26T03:00:00Z"))),
                    List.of()));

    TravelAgentService.Result result =
        agent.execute("8月30日从常州出发去上海玩2天，2人预算5000元");

    assertTrue(result.markdownDocument().contains("## 往返交通参考"));
    assertTrue(result.markdownDocument().contains("G7001"));
    assertTrue(result.markdownDocument().contains("去程（推荐）"));
    assertTrue(result.markdownDocument().contains("地图参考评分：4.8"));
    assertTrue(result.markdownDocument().contains("人民大道201号"));
    assertTrue(result.markdownDocument().contains("往返大交通（动态参考） | 598 元"));
    assertEquals(
        5000,
        result.plan().budget().total()
            + result.plan().budget().remaining()
            + result.plan().mapData().referenceRoundTripCost(2));
  }

  @Test
  void explainsWhenMapProviderReturnsNoCompleteTransitPlan() throws Exception {
    List<InMemoryTravelVectorStore.Hit> hits = attractions("上海", 4);
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> hits,
            (city, startDate, days) -> sunnyForecast(city, startDate, days),
            (brief, plan) ->
                new TravelMapData(
                    true,
                    brief.origin(),
                    brief.destination(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    List.of()));

    TravelAgentService.Result result =
        agent.execute("从常州出发去上海玩2天，2人预算3000元");

    assertTrue(result.completed());
    assertTrue(result.markdownDocument().contains("## 往返交通参考"));
    assertTrue(result.markdownDocument().contains("动态交通服务暂未返回可用班次"));
    assertTrue(result.markdownDocument().contains("百度地图查询去程"));
    assertTrue(result.markdownDocument().contains("铁路 12306 查询"));
    assertTrue(result.markdownDocument().contains("已查询从常州到上海的往返交通"));
    assertTrue(result.markdownDocument().contains("暂未取得完整方案"));
  }

  private static List<InMemoryTravelVectorStore.Hit> attractions(String city, int count) {
    List<InMemoryTravelVectorStore.Hit> hits = new ArrayList<>();
    for (int index = 1; index <= count; index++) {
      TravelKnowledgeChunk chunk =
          new TravelKnowledgeChunk(
              "attraction:test-" + index,
              city,
              "attraction",
              "测试景点" + index,
              "测试景点位于" + city + "；类别：博物馆；亮点：历史文化",
              "test",
              List.of());
      hits.add(new InMemoryTravelVectorStore.Hit(chunk, 1.0 - index * 0.01));
    }
    return List.copyOf(hits);
  }

  private static TravelForecast sunnyForecast(
      String city, java.time.LocalDate startDate, int days) {
    return new TravelForecast(
        city,
        startDate,
        IntStream.range(0, days)
            .mapToObj(
                index ->
                    new TravelForecast.Daily(
                        startDate.plusDays(index), true, "晴", 18, 25, 0, 8))
            .toList());
  }
}
