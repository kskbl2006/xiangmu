package com.github.wechat.ilink.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.agent.CnTripPlannerSkill;
import com.github.wechat.ilink.bot.agent.QwenCityResolver;
import com.github.wechat.ilink.bot.agent.TravelAgentService;
import com.github.wechat.ilink.bot.agent.TravelBriefSkill;
import com.github.wechat.ilink.bot.agent.TravelPlanReviewSkill;
import com.github.wechat.ilink.bot.agent.TravelPdfRenderer;
import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.maps.BaiduMapClient;
import com.github.wechat.ilink.bot.maps.TravelMapService;
import com.github.wechat.ilink.bot.maps.JuheRailClient;
import com.github.wechat.ilink.bot.maps.TravelEvidenceService;
import com.github.wechat.ilink.bot.travelrag.TravelRagService;
import com.github.wechat.ilink.bot.weather.OpenMeteoWeather;

/** 单目标旅行 Agent MVP 的真实 API 冒烟测试。 */
public final class TravelAgentSmokeTest {
  private TravelAgentSmokeTest() {}

  public static void main(String[] args) throws Exception {
    String goal =
        args.length == 0
            ? "帮我规划上海三日游，2人预算5000元，喜欢历史、美食和夜景，节奏轻松"
            : String.join(" ", args);
    AppConfig config = AppConfig.fromEnvironment();
    TravelRagService rag = TravelRagService.fromBundledIndex(config, new ObjectMapper());
    OpenMeteoWeather weather = new OpenMeteoWeather(config);
    TravelMapService mapService =
        new TravelMapService(new BaiduMapClient(config), config.getBaiduMapMaxPoiQueries());
    TravelEvidenceService evidenceService =
        new TravelEvidenceService(mapService, new JuheRailClient(config));
    com.github.wechat.ilink.bot.llm.QwenClient qwenClient =
        new com.github.wechat.ilink.bot.llm.QwenClient(config);
    TravelAgentService agent =
        new TravelAgentService(
            new TravelBriefSkill(new QwenCityResolver(qwenClient)),
            new CnTripPlannerSkill(qwenClient),
            new TravelPlanReviewSkill(),
            rag::retrieveAttractionsForPlanning,
            weather::forecast,
            evidenceService);

    TravelAgentService.Result result = agent.execute(goal);
    System.out.println(result.reply());
    if (result.markdownDocument() != null) {
      System.out.println(result.markdownDocument());
      byte[] pdf = new TravelPdfRenderer(config).render(result.markdownDocument());
      System.out.println("PDF rendered: " + pdf.length + " bytes");
    }
    if (!result.completed()) {
      throw new IllegalStateException("Travel Agent MVP did not complete the goal");
    }
  }
}
