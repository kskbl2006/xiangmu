package com.github.wechat.ilink.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.agent.CnTripPlannerSkill;
import com.github.wechat.ilink.bot.agent.QwenCityResolver;
import com.github.wechat.ilink.bot.agent.TravelAgentService;
import com.github.wechat.ilink.bot.agent.TravelBriefSkill;
import com.github.wechat.ilink.bot.agent.TravelPlan;
import com.github.wechat.ilink.bot.agent.TravelPlanReviewSkill;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.maps.BaiduMapClient;
import com.github.wechat.ilink.bot.maps.TravelMapService;
import com.github.wechat.ilink.bot.travelrag.TravelRagService;
import com.github.wechat.ilink.bot.weather.OpenMeteoWeather;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Real-provider business evaluation for the supported travel MVP scope. */
public final class TravelAgentEvaluation {
  private TravelAgentEvaluation() {}

  public static void main(String[] args) throws Exception {
    AppConfig config = AppConfig.fromEnvironment();
    boolean mapEnabled = Arrays.stream(args).noneMatch("--no-map"::equals);
    TravelRagService rag = TravelRagService.fromBundledIndex(config, new ObjectMapper());
    OpenMeteoWeather weather = new OpenMeteoWeather(config);
    TravelMapService mapService =
        new TravelMapService(new BaiduMapClient(config), config.getBaiduMapMaxPoiQueries());
    com.github.wechat.ilink.bot.llm.QwenClient qwenClient =
        new com.github.wechat.ilink.bot.llm.QwenClient(config);
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(new QwenCityResolver(qwenClient)),
            new CnTripPlannerSkill(qwenClient),
            new TravelPlanReviewSkill(),
            rag::retrieveAttractionsForPlanning,
            weather::forecast,
            mapEnabled
                ? mapService::enrich
                : (brief, plan) ->
                    com.github.wechat.ilink.bot.agent.TravelMapData.disabled(
                        brief.destination()));

    List<Case> cases =
        List.of(
            new Case("上海平衡行程", "从常州出发规划上海3天旅行，2人预算5000元，喜欢历史和夜景", true, "上海", 3),
            new Case("杭州轻松行程", "从南京出发帮我做杭州两日游，一个人预算1800元，喜欢自然和文化，节奏轻松", true, "杭州", 2),
            new Case("苏州亲子行程", "一家三口从无锡出发去苏州玩2天，预算3000元，喜欢园林、博物馆和亲子", true, "苏州", 2),
            new Case("苏州默认参数", "南京出发帮我做苏州旅行攻略", true, "苏州", 3),
            new Case("北京知识兜底", "三个人从常州去北京玩3天，预算5000元，喜欢历史和夜景", true, "北京", 3),
            new Case("成都知识兜底", "情侣从重庆出发去成都两日游，预算3000元，喜欢美食，节奏轻松", true, "成都", 2));

    int passed = 0;
    long startedAt = System.nanoTime();
    for (Case testCase : cases) {
      TravelAgentService.Result result = agent.execute(testCase.goal());
      validate(testCase, result);
      passed++;
      System.out.printf(
          "PASS\t%s\tcompleted=%s\tdays=%d\treviewRounds=%d%n",
          testCase.name(),
          result.completed(),
          result.plan() == null ? 0 : result.plan().days().size(),
          result.plan() == null ? 0 : result.plan().reviewRounds());
    }
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
    System.out.printf(
        "Business evaluation: %d/%d passed, mapEnabled=%s, elapsed=%d ms%n",
        passed,
        cases.size(),
        mapEnabled,
        elapsedMillis);
  }

  private static void validate(Case expected, TravelAgentService.Result result) {
    if (result.completed() != expected.shouldComplete()) {
      throw new IllegalStateException(expected.name() + " completion mismatch");
    }
    if (!expected.shouldComplete()) return;
    TravelPlan plan = result.plan();
    if (!expected.destination().equals(plan.brief().destination())
        || expected.days() != plan.days().size()) {
      throw new IllegalStateException(expected.name() + " normalized brief mismatch");
    }
    if (plan.budget().total() != plan.brief().budgetYuan()) {
      throw new IllegalStateException(expected.name() + " budget mismatch");
    }
    if (plan.days().stream().anyMatch(day -> day.activities().isEmpty())) {
      throw new IllegalStateException(expected.name() + " contains empty day");
    }
    Set<String> titles = new HashSet<>();
    boolean duplicate =
        plan.days().stream()
            .flatMap(day -> day.activities().stream())
            .map(TravelPlan.Activity::title)
            .anyMatch(title -> !titles.add(title));
    if (duplicate) throw new IllegalStateException(expected.name() + " contains duplicate activity");
    if (result.markdownDocument() == null
        || !result.markdownDocument().startsWith("# ")
        || !result.markdownDocument().contains("## 预算分配")) {
      throw new IllegalStateException(expected.name() + " missing Markdown delivery");
    }
  }

  private record Case(
      String name, String goal, boolean shouldComplete, String destination, int days) {}
}
