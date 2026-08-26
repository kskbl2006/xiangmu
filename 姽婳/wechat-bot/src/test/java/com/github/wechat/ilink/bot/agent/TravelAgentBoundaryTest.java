package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class TravelAgentBoundaryTest {
  private static final Map<String, String> CITY_CASES =
      Map.of(
          "北京", "三个人从常州去北京玩3天，预算5000元，喜欢历史、美食和夜景",
          "成都", "情侣从重庆出发去成都两日游，预算3000元，喜欢美食，节奏轻松",
          "广州", "一家四口从深圳出发去广州玩4天，预算8000元，喜欢亲子和自然",
          "重庆", "一个人从成都出发去重庆旅行3天，预算不限，喜欢夜景",
          "西安", "十二人从郑州出发去西安两日游，预算12000元，喜欢历史和博物馆");

  @Test
  void completesStableArtifactsForDifferentChineseCitiesAndScenarios() throws Exception {
    for (var testCase : CITY_CASES.entrySet()) {
      String expectedCity = testCase.getKey();
      TravelAgentService agent = generalCityAgent(expectedCity, availableForecast(expectedCity, 7));

      TravelAgentService.Result result = agent.execute(testCase.getValue());

      assertTrue(result.completed(), expectedCity);
      assertEquals(expectedCity, result.plan().brief().destination());
      assertEquals(result.plan().brief().days(), result.plan().days().size());
      assertFalse(result.plan().days().stream().anyMatch(day -> day.activities().isEmpty()));
      assertNotNull(result.markdownDocument());
      assertTrue(result.markdownDocument().startsWith("# " + expectedCity), expectedCity);
      assertTrue(result.markdownDocument().contains("## 预算分配"), expectedCity);
      assertTrue(result.fileName().endsWith("旅行方案.md"));
    }
  }

  @Test
  void degradesSafelyWhenDestinationOrWeatherCannotBeResolved() throws Exception {
    TravelAgentService missingDestination =
        new TravelAgentService(
            new TravelBriefSkill(message -> ""),
            new CnTripPlannerSkill(),
            new TravelPlanReviewSkill(),
            (query, limit) -> List.of(),
            (city, startDate, days) -> TravelForecast.unavailable(city, startDate, days));
    TravelAgentService.Result incomplete = missingDestination.execute("帮我安排一次旅行");
    assertFalse(incomplete.completed());
    assertTrue(incomplete.reply().contains("目的地"));
    assertEquals(null, incomplete.markdownDocument());
    assertEquals(TravelAgentService.MissingInput.DESTINATION, incomplete.missingInput());

    TravelAgentService unavailableWeather =
        generalCityAgent("哈尔滨", TravelForecast.unavailable("哈尔滨", LocalDate.now(), 3));
    TravelAgentService.Result result = unavailableWeather.execute("从长春出发去哈尔滨玩3天，预算3000元");
    assertTrue(result.completed());
    assertTrue(result.markdownDocument().contains("天气暂不可查询"));
    assertFalse(result.markdownDocument().contains("天气晴朗"));
    assertFalse(result.markdownDocument().contains("晴天"));
  }

  @Test
  void exposesLowBudgetRiskAndKeepsPdfReadable() throws Exception {
    TravelAgentService agent = generalCityAgent("武汉", availableForecast("武汉", 2));
    TravelAgentService.Result result = agent.execute("两个人从长沙出发去武汉玩2天，预算200元");

    assertTrue(result.completed());
    assertTrue(result.markdownDocument().contains("人均每日预算低于150元"));

    TravelPdfRenderer renderer =
        new TravelPdfRenderer("/System/Library/Fonts/Supplemental/Arial Unicode.ttf");
    byte[] pdf = renderer.render(result.markdownDocument());
    try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
      String extracted = new PDFTextStripper().getText(document);
      assertTrue(extracted.contains("武汉 2 天旅行方案"));
      assertTrue(extracted.contains("人均每日预算低于150元"));
      assertFalse(extracted.contains("|---"));
    }
  }

  private static TravelAgentService generalCityAgent(String destination, TravelForecast forecast) {
    return new TravelAgentService(
        new TravelBriefSkill(message -> destination),
        new CnTripPlannerSkill(),
        new TravelPlanReviewSkill(),
        (query, limit) -> {
          throw new AssertionError("知识库外城市不得误用其他城市 RAG");
        },
        (city, startDate, days) -> align(forecast, city, startDate, days));
  }

  private static TravelForecast availableForecast(String city, int days) {
    return align(null, city, LocalDate.now(), days);
  }

  private static TravelForecast align(
      TravelForecast template, String city, LocalDate startDate, int days) {
    if (template != null && !template.anyAvailable()) {
      return TravelForecast.unavailable(city, startDate, days);
    }
    return new TravelForecast(
        city,
        startDate,
        IntStream.range(0, days)
            .mapToObj(
                index ->
                    new TravelForecast.Daily(
                        startDate.plusDays(index), true, index == 1 ? "小雨" : "多云", 18, 27, index == 1 ? 70 : 20, 12))
            .toList());
  }
}
