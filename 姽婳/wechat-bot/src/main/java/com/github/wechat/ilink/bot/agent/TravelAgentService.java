package com.github.wechat.ilink.bot.agent;

import com.github.wechat.ilink.bot.agent.TravelPlan.Activity;
import com.github.wechat.ilink.bot.agent.TravelPlan.DayPlan;
import com.github.wechat.ilink.bot.agent.TravelPlan.ReviewIssue;
import com.github.wechat.ilink.bot.agent.TravelPlan.Severity;
import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs the travel MVP from one high-level goal through tools, Skills, review and delivery. */
public final class TravelAgentService {
  private static final Logger log = LoggerFactory.getLogger(TravelAgentService.class);
  private static final int MAX_REVIEW_ROUNDS = 2;

  @FunctionalInterface
  public interface KnowledgeSearch {
    List<InMemoryTravelVectorStore.Hit> search(String query, int limit) throws Exception;
  }

  @FunctionalInterface
  public interface WeatherLookup {
    TravelForecast forecast(String city, java.time.LocalDate startDate, int days) throws Exception;
  }

  @FunctionalInterface
  public interface MapEnrichment {
    TravelMapData enrich(TravelBrief brief, TravelPlan plan);
  }

  private final TravelBriefSkill briefSkill;
  private final CnTripPlannerSkill plannerSkill;
  private final TravelPlanReviewSkill reviewSkill;
  private final KnowledgeSearch knowledgeSearch;
  private final WeatherLookup weatherLookup;
  private final MapEnrichment mapEnrichment;

  public TravelAgentService(
      TravelBriefSkill briefSkill,
      CnTripPlannerSkill plannerSkill,
      TravelPlanReviewSkill reviewSkill,
      KnowledgeSearch knowledgeSearch,
      WeatherLookup weatherLookup) {
    this(
        briefSkill,
        plannerSkill,
        reviewSkill,
        knowledgeSearch,
        weatherLookup,
        (brief, plan) -> TravelMapData.disabled(brief.destination()));
  }

  public TravelAgentService(
      TravelBriefSkill briefSkill,
      CnTripPlannerSkill plannerSkill,
      TravelPlanReviewSkill reviewSkill,
      KnowledgeSearch knowledgeSearch,
      WeatherLookup weatherLookup,
      MapEnrichment mapEnrichment) {
    this.briefSkill = briefSkill;
    this.plannerSkill = plannerSkill;
    this.reviewSkill = reviewSkill;
    this.knowledgeSearch = knowledgeSearch;
    this.weatherLookup = weatherLookup;
    this.mapEnrichment = mapEnrichment;
  }

  public boolean supports(String message) {
    return briefSkill.supports(message);
  }

  public Result execute(String highLevelGoal) throws Exception {
    return execute(highLevelGoal, ignored -> {});
  }

  public Result execute(String highLevelGoal, Consumer<String> progress) throws Exception {
    long startedAt = System.nanoTime();
    Consumer<String> reporter = progress == null ? ignored -> {} : progress;
    TravelBrief brief = briefSkill.normalize(highLevelGoal);
    reporter.accept("需求已解析");
    if (!brief.supported()) {
      return new Result(
          false,
          "我还无法确定你的旅行目的地，请告诉我想去哪个中国城市。",
          null,
          null,
          MissingInput.DESTINATION);
    }
    if (brief.origin().isBlank()) {
      return new Result(
          false,
          "为了生成包含往返交通的完整方案，请告诉我从哪个城市出发。",
          null,
          null,
          MissingInput.ORIGIN);
    }

    CompletableFuture<TravelForecast> forecastFuture;
    CompletableFuture<List<InMemoryTravelVectorStore.Hit>> knowledgeFuture;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      forecastFuture =
          CompletableFuture.supplyAsync(() -> safeForecast(brief), executor);
      knowledgeFuture =
          CompletableFuture.supplyAsync(() -> safeKnowledgeSearch(highLevelGoal, brief), executor);
      CompletableFuture.allOf(forecastFuture, knowledgeFuture).join();
    }
    TravelForecast forecast = forecastFuture.join();
    List<InMemoryTravelVectorStore.Hit> hits = knowledgeFuture.join();
    reporter.accept("天气与知识已并行查询");
    TravelPlan plan = plannerSkill.build(brief, forecast, hits);
    reporter.accept("行程已规划");
    TravelMapData mapData = mapEnrichment.enrich(brief, plan);
    reporter.accept("地图数据已补充");
    int transportReference = mapData.referenceRoundTripCost(brief.travelers());
    if (transportReference > 0) {
      plan =
          new TravelPlan(
              plan.brief(),
              plan.forecast(),
              plan.mapData(),
              plan.days(),
              CnTripPlannerSkill.allocateBudget(
                  Math.max(0, brief.budgetYuan() - transportReference)),
              plan.generationMode(),
              plan.executionSteps(),
              plan.reviewRounds(),
              plan.reviewIssues());
    }
    plan =
        new TravelPlan(
            plan.brief(),
            plan.forecast(),
            mapData,
            plan.days(),
            plan.budget(),
            plan.generationMode(),
            plan.executionSteps(),
            plan.reviewRounds(),
            plan.reviewIssues());

    TravelPlanReviewSkill.ReviewResult review = reviewSkill.review(plan);
    while (!review.passed() && plan.reviewRounds() < MAX_REVIEW_ROUNDS) {
      plan = reviewSkill.repair(plan, review.issues());
      review = reviewSkill.review(plan);
    }
    plan =
        new TravelPlan(
            plan.brief(),
            plan.forecast(),
            plan.mapData(),
            plan.days(),
            plan.budget(),
            plan.generationMode(),
            plan.executionSteps(),
            plan.reviewRounds(),
            review.issues());
    reporter.accept("方案已审校");
    log.info(
        "Travel Agent completed: destination={}, days={}, skills=[{}, {}], reviewRounds={}, issues={}, elapsedMs={}",
        brief.destination(),
        brief.days(),
        briefSkill.definition().name(),
        reviewSkill.definition().name(),
        plan.reviewRounds(),
        plan.reviewIssues().size(),
        (System.nanoTime() - startedAt) / 1_000_000L);
    String notice =
        review.passed()
            ? brief.destination() + brief.days() + "天旅行方案已生成，请查收 Markdown 与 PDF 附件。"
            : "旅行方案已生成，但仍有约束未满足，请查看附件末尾说明。";
    return new Result(review.passed(), notice, renderMarkdown(plan, review.passed()), plan);
  }

  private TravelForecast safeForecast(TravelBrief brief) {
    try {
      return weatherLookup.forecast(brief.destination(), brief.startDate(), brief.days());
    } catch (Exception e) {
      log.warn("Travel agent weather step failed for {}: {}", brief.destination(), e.getMessage());
      return TravelForecast.unavailable(brief.destination(), brief.startDate(), brief.days());
    }
  }

  private List<InMemoryTravelVectorStore.Hit> safeKnowledgeSearch(
      String highLevelGoal, TravelBrief brief) {
    if (!brief.knowledgeCovered()) {
      log.info(
          "Travel destination {} is outside the bundled knowledge base; using Qwen general-knowledge fallback",
          brief.destination());
      return List.of();
    }
    String query =
        highLevelGoal
            + " "
            + brief.destination()
            + " 景点 行程 "
            + String.join(" ", brief.interests())
            + " 雨天室内备选";
    int requestedHits = Math.min(20, Math.max(10, brief.days() * 3));
    try {
      return knowledgeSearch.search(query, requestedHits);
    } catch (Exception e) {
      log.warn("Travel knowledge retrieval failed for {}: {}", brief.destination(), e.getMessage());
      return List.of();
    }
  }

  static String renderMarkdown(TravelPlan plan, boolean passed) {
    StringBuilder text = new StringBuilder();
    TravelBrief brief = plan.brief();
    text.append("# ").append(brief.destination()).append(" ").append(brief.days()).append(" 天旅行方案\n\n")
        .append("> **人数：** ").append(brief.travelers()).append(" 人　")
        .append("**预算：** ").append(brief.budgetYuan()).append(" 元　")
        .append("**节奏：** ")
        .append("relaxed".equals(brief.pace()) ? "轻松" : "packed".equals(brief.pace()) ? "紧凑" : "平衡")
        .append("\n\n")
        .append("## 天气参考")
        .append(plan.forecast().place().isBlank() ? "" : "（" + plan.forecast().place() + "）")
        .append("\n\n")
        .append("| 日期 | 天气 | 温度 | 最大降水概率 | 最大风速 |\n")
        .append("|---|---|---:|---:|---:|\n");
    for (TravelForecast.Daily weather : plan.forecast().days()) {
      if (weather.available()) {
        text.append(String.format(
            Locale.ROOT,
            "| %s | %s | %.1f～%.1f℃ | %d%% | %.1f km/h |%n",
            weather.date(),
            weather.condition(),
            weather.minTemperatureC(),
            weather.maxTemperatureC(),
            weather.precipitationProbabilityMax(),
            weather.windSpeedMaxKmh()));
      } else {
        text.append("| ").append(weather.date()).append(" | 暂不可查询 | — | — | — |\n");
      }
    }
    appendTransit(text, brief, plan.mapData());
    for (DayPlan day : plan.days()) {
      text.append("\n## 第 ").append(day.day()).append(" 天：")
          .append(day.theme().isBlank() ? "城市精选" : day.theme())
          .append("（").append(brief.startDate().plusDays(day.day() - 1L)).append("）")
          .append("\n\n");
      for (Activity activity : day.activities()) {
        text.append("### ").append(activity.period()).append(" · ").append(activity.title()).append("\n\n")
            .append(activity.note()).append("\n\n");
        TravelMapData.PoiSnapshot poi = plan.mapData().poi(activity.title());
        if (poi != null) {
          List<String> facts = new ArrayList<>();
          if (!poi.address().isBlank()) facts.add("地址：" + poi.address());
          if (poi.rating() > 0) {
            facts.add(String.format(Locale.ROOT, "地图参考评分：%.1f", poi.rating()));
          }
          if (!poi.openingHours().isBlank()) facts.add("开放时间：" + poi.openingHours());
          if (poi.referencePriceYuan() > 0) {
            facts.add(String.format(Locale.ROOT, "参考费用：%.0f 元", poi.referencePriceYuan()));
          }
          if (!facts.isEmpty()) {
            text.append("> ").append(String.join("　|　", facts)).append("\n\n");
          }
          if (!poi.detailUrl().isBlank()) {
            text.append("[查看地图详情](").append(poi.detailUrl()).append(")\n\n");
          }
        }
      }
      if (!day.mealSuggestion().isBlank()) {
        text.append("**用餐建议：** ").append(day.mealSuggestion()).append("\n");
      }
    }
    TravelPlan.Budget budget = plan.budget();
    int transportReference = plan.mapData().referenceRoundTripCost(brief.travelers());
    text.append("\n## 预算分配\n\n")
        .append("| 类别 | 预算 |\n|---|---:|\n")
        .append(String.format(Locale.ROOT, "| 往返大交通（动态参考） | %d 元 |%n", transportReference))
        .append(String.format(Locale.ROOT, "| 住宿 | %d 元 |%n", budget.lodging()))
        .append(String.format(Locale.ROOT, "| 餐饮 | %d 元 |%n", budget.food()))
        .append(String.format(Locale.ROOT, "| 市内交通 | %d 元 |%n", budget.localTransport()))
        .append(String.format(Locale.ROOT, "| 门票 | %d 元 |%n", budget.tickets()))
        .append(String.format(Locale.ROOT, "| 机动资金 | %d 元 |%n", budget.buffer()))
        .append(
            String.format(
                Locale.ROOT,
                "| **预计总支出** | **%d 元** |%n",
                budget.total() + transportReference));
    String transportNotice;
    if (transportReference > 0) {
      transportNotice = "- 往返交通采用地图候选中的最低参考价，实际票价和余票请通过官方平台确认。\n";
    } else if (!plan.mapData().enabled()) {
      transportNotice = "- 当前未启用地图交通服务，往返交通请通过官方交通平台查询。\n";
    } else {
      transportNotice =
          "- 已查询从"
              + brief.origin()
              + "到"
              + brief.destination()
              + "的往返交通，但暂未取得完整方案，请通过官方交通平台确认。\n";
    }
    text.append("\n## 使用说明\n\n")
        .append("- 天气预报会随时间变化，请在出发前再次确认。\n")
        .append("- 票价、营业时间和预约政策可能变化，出发前请通过官方渠道确认。\n")
        .append(transportNotice);
    for (String warning : plan.mapData().warnings()) {
      if (!isTransitWarning(warning)) {
        text.append("- ").append(warning).append("。\n");
      }
    }
    if (!plan.forecast().fullyAvailable()) {
      text.append("- 部分或全部行程日期超出当前天气预报范围，相关日期天气暂不可查询；方案已按常规出行条件编排。\n");
    }
    if (!brief.knowledgeCovered()) {
      text.append("- 该目的地暂未收录进本地知识库，行程基于模型通用知识生成，动态信息请出发前确认。\n");
    }
    if (!brief.assumptions().isEmpty()) {
      brief.assumptions().stream()
          .filter(value -> !value.contains("往返大交通"))
          .forEach(value -> text.append("- ").append(value).append("\n"));
    }
    List<ReviewIssue> warnings =
        plan.reviewIssues().stream().filter(issue -> issue.severity() != Severity.INFO).toList();
    for (ReviewIssue issue : warnings) {
      text.append("- ").append(issue.message()).append("；").append(issue.suggestion()).append("\n");
    }
    text.append("\n---\n\n")
        .append("*生成方式：")
        .append(generationDescription(plan.generationMode()))
        .append("；校验结果：").append(passed ? "通过" : "存在待处理约束")
        .append("。*\n");
    return text.toString().trim();
  }

  private static void appendTransit(
      StringBuilder text, TravelBrief brief, TravelMapData mapData) {
    String origin = brief.origin();
    String destination = brief.destination();
    if (origin.isBlank() || destination.isBlank() || origin.equals(destination)) return;

    java.time.LocalDate outboundDate = brief.startDate();
    java.time.LocalDate returnDate = brief.startDate().plusDays(brief.days() - 1L);
    boolean outboundMissing = mapData.outboundRoutes().isEmpty();
    boolean returnMissing = mapData.returnRoutes().isEmpty();

    text.append("\n## 往返交通参考\n\n");
    if (!mapData.enabled() || (outboundMissing && returnMissing)) {
      String status = mapData.enabled() ? "地图暂未返回可用班次" : "地图动态查询未启用";
      text.append("| 方向 | 日期 | 路线 | 查询状态 |\n")
          .append("|---|---|---|---|\n")
          .append("| 去程 | ").append(outboundDate).append(" | ")
          .append(origin).append(" → ").append(destination).append(" | ").append(status).append(" |\n")
          .append("| 返程 | ").append(returnDate).append(" | ")
          .append(destination).append(" → ").append(origin).append(" | ").append(status).append(" |\n\n");
      if (mapData.enabled()) {
        text.append("所选日期较远、线路覆盖不足或班次尚未开放时，地图服务可能暂不返回候选。")
            .append("建议优先在 12306 比较高铁或动车；长距离出行可同时比较航班。")
            .append("具体车次与票价请通过官方平台确认。\n\n");
      } else {
        text.append("行程仍保留，具体车次与票价请通过官方平台确认。\n\n");
      }
    } else {
      text.append("以下为地图服务在生成方案时返回的候选，价格与班次不代表余票或最终成交信息。\n\n")
          .append("| 方向 | 日期 | 交通 | 班次 | 发到站 | 发到时间 | 预计耗时 | 参考价格 |\n")
          .append("|---|---|---|---|---|---|---:|---:|\n");
      appendRouteRows(text, "去程", origin + " → " + destination, mapData.outboundRoutes());
      appendRouteRows(text, "返程", destination + " → " + origin, mapData.returnRoutes());
      text.append("\n");
      if (outboundMissing) {
        text.append("- 去程（").append(outboundDate).append("）暂未返回可用班次。\n");
      }
      if (returnMissing) {
        text.append("- 返程（").append(returnDate).append("）暂未返回可用班次。\n");
      }
      if (outboundMissing || returnMissing) text.append("\n");
    }

    text.append("- [百度地图查询去程](")
        .append(baiduDirectionUrl(origin, destination))
        .append(")\n")
        .append("- [百度地图查询返程](")
        .append(baiduDirectionUrl(destination, origin))
        .append(")\n")
        .append("- [铁路 12306 查询](https://www.12306.cn/)\n");
  }

  private static String baiduDirectionUrl(String origin, String destination) {
    String encodedOrigin = URLEncoder.encode(origin, StandardCharsets.UTF_8);
    String encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8);
    return "https://api.map.baidu.com/direction?origin="
        + encodedOrigin
        + "&destination="
        + encodedDestination
        + "&mode=transit&origin_region="
        + encodedOrigin
        + "&destination_region="
        + encodedDestination
        + "&output=html&src=webapp.guihua.wechatbot";
  }

  private static boolean isTransitWarning(String warning) {
    return warning != null
        && (warning.contains("往返交通")
            || warning.contains("交通候选")
            || warning.contains("公交方案"));
  }

  private static void appendRouteRows(
      StringBuilder text,
      String direction,
      String places,
      List<TravelMapData.RouteOption> routes) {
    for (TravelMapData.RouteOption route : routes) {
      String price =
          route.referencePriceYuan() > 0
              ? String.format(Locale.ROOT, "%.0f 元", route.referencePriceYuan())
              : "待确认";
      String duration = route.durationMinutes() > 0 ? route.durationMinutes() + " 分钟" : "待确认";
      String serviceName =
          route.bookingUrl().isBlank()
              ? markdownCell(route.serviceName())
              : "[" + markdownCell(route.serviceName()) + "](" + route.bookingUrl() + ")";
      text.append("| ").append(direction).append("（").append(places).append("） | ")
          .append(route.date()).append(" | ").append(route.mode()).append(" | ")
          .append(serviceName).append(" | ")
          .append(markdownCell(route.departureStation())).append(" → ")
          .append(markdownCell(route.arrivalStation())).append(" | ")
          .append(markdownCell(route.departureTime())).append(" → ")
          .append(markdownCell(route.arrivalTime())).append(" | ")
          .append(duration).append(" | ").append(price).append(" |\n");
    }
  }

  private static String markdownCell(String value) {
    return value == null ? "" : value.replace("|", "／").replace("\n", " ").trim();
  }

  private static String generationDescription(String generationMode) {
    return switch (generationMode) {
      case "qwen-structured-planning" -> "千问结构化规划 + RAG 检索 + 约束审校";
      case "qwen-general-knowledge-planning" -> "千问通用知识规划 + 约束审校";
      case "local-rule-fallback", "local-rule-planning" -> "本地规则兜底 + 约束审校";
      default -> "结构化规划 + 约束审校";
    };
  }

  public enum MissingInput {
    NONE,
    ORIGIN,
    DESTINATION
  }

  public record Result(
      boolean completed,
      String reply,
      String markdownDocument,
      TravelPlan plan,
      MissingInput missingInput) {
    public Result(boolean completed, String reply, String markdownDocument, TravelPlan plan) {
      this(completed, reply, markdownDocument, plan, MissingInput.NONE);
    }

    public Result {
      missingInput = missingInput == null ? MissingInput.NONE : missingInput;
    }

    public String fileName() {
      return plan == null
          ? "旅行方案.md"
          : plan.brief().destination() + "-" + plan.brief().days() + "天旅行方案.md";
    }
  }
}
