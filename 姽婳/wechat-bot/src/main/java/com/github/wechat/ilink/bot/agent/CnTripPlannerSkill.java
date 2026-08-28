package com.github.wechat.ilink.bot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.bot.llm.QwenClient;
import com.github.wechat.ilink.bot.travelrag.InMemoryTravelVectorStore;
import com.github.wechat.ilink.bot.travelrag.TravelKnowledgeChunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 基于千问生成行程，并提供稳定的本地兜底。 */
public final class CnTripPlannerSkill {
  private static final Logger log = LoggerFactory.getLogger(CnTripPlannerSkill.class);
  private static final Set<String> OUTDOOR_WORDS =
      Set.of("外滩", "湖", "湿地", "古街", "园林", "动物园", "山", "步行", "夜景", "公园");
  private static final String SYSTEM_INSTRUCTION =
      "你是中国城市旅行行程编排器。只能从用户提供的候选景点中选择，不得改写sourceId或虚构景点。"
          + "结合人数、预算、兴趣、节奏和当前天气安排每日主题。必须返回单个JSON对象，不要Markdown。"
          + "格式为{\"days\":[{\"day\":1,\"theme\":\"主题\",\"mealSuggestion\":\"同区域用餐建议\","
          + "\"activities\":[{\"period\":\"上午\",\"sourceId\":\"候选ID\",\"reason\":\"推荐理由\"}]}]}。"
          + "dailyWeather中的day与行程天数一一对应；降水概率达到60%时每天最多选择一个outdoor候选，"
          + "达到80%或遇到雷暴、大雨时只选择outdoor=false的室内候选。"
          + "天数必须完整，景点不得重复。每条reason控制在50个汉字内。用餐建议只写活动区域和菜系，禁止虚构具体店名。"
          + "relaxed每天2项且下午项可选，balanced每天2项，packed每天最多3项。";
  private static final String GENERAL_KNOWLEDGE_INSTRUCTION =
      "你是中国城市旅行行程编排器。用户目的地未收录进本地知识库，请仅使用你有把握的城市常识安排知名且稳定存在的景点。"
          + "不得伪造知识来源、sourceId、实时余票、营业状态、精确票价、预约期限或具体餐厅。"
          + "结合人数、预算、兴趣、节奏和当前天气生成行程。必须返回单个JSON对象，不要Markdown。"
          + "格式为{\"days\":[{\"day\":1,\"theme\":\"主题\",\"mealSuggestion\":\"同区域用餐建议\","
          + "\"activities\":[{\"period\":\"上午\",\"title\":\"景点\",\"reason\":\"推荐理由\",\"outdoor\":true}]}]}。"
          + "dailyWeather中的day与行程天数一一对应；降水概率达到60%时每天最多安排一个户外项目，"
          + "达到80%或遇到雷暴、大雨时只安排室内项目。"
          + "forecastAvailable=false时内部按晴天条件正常编排行程，回复中不得声称当天实际晴朗。"
          + "天数必须完整，景点不得重复。每条reason控制在50个汉字内。用餐建议只写区域和菜系。"
          + "relaxed每天2项且下午项可选，balanced每天2项，packed每天最多3项。";

  private final QwenClient qwenClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public CnTripPlannerSkill() {
    this(null);
  }

  public CnTripPlannerSkill(QwenClient qwenClient) {
    this.qwenClient = qwenClient;
  }

  public TravelPlan build(
      TravelBrief brief,
      TravelForecast forecast,
      List<InMemoryTravelVectorStore.Hit> hits) {
    return build(brief, forecast, hits, brief.budgetYuan());
  }

  public TravelPlan build(
      TravelBrief brief,
      TravelForecast forecast,
      List<InMemoryTravelVectorStore.Hit> hits,
      int destinationBudgetYuan) {
    List<TravelKnowledgeChunk> attractions = uniqueAttractions(hits);
    if (qwenClient != null && attractions.size() >= brief.days()) {
      long startedAt = System.nanoTime();
      try {
        String json =
            qwenClient.chatJson(
                SYSTEM_INSTRUCTION,
                buildModelInput(brief, forecast, attractions, destinationBudgetYuan));
        TravelPlan plan = parseModelPlan(brief, forecast, attractions, json);
        log.info(
            "Qwen generated structured travel plan: days={}, elapsedMs={}",
            plan.days().size(),
            (System.nanoTime() - startedAt) / 1_000_000L);
        return plan;
      } catch (Exception e) {
        log.warn("Qwen travel planning failed; using local fallback: {}", e.getMessage());
        return buildLocal(brief, forecast, attractions, "local-rule-fallback");
      }
    }
    if (qwenClient != null) {
      long startedAt = System.nanoTime();
      try {
        String json =
            qwenClient.chatJson(
                GENERAL_KNOWLEDGE_INSTRUCTION,
                buildGeneralModelInput(brief, forecast, destinationBudgetYuan));
        TravelPlan plan = parseGeneralModelPlan(brief, forecast, json);
        log.info(
            "Qwen generated general-knowledge travel plan: destination={}, days={}, elapsedMs={}",
            brief.destination(),
            plan.days().size(),
            (System.nanoTime() - startedAt) / 1_000_000L);
        return plan;
      } catch (Exception e) {
        log.warn("Qwen general-knowledge planning failed; using local fallback: {}", e.getMessage());
        return buildLocal(brief, forecast, attractions, "local-rule-fallback");
      }
    }
    return buildLocal(brief, forecast, attractions, "local-rule-planning");
  }

  private String buildGeneralModelInput(
      TravelBrief brief, TravelForecast forecast, int destinationBudgetYuan) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("destination", brief.destination());
    root.put("days", brief.days());
    root.put("travelers", brief.travelers());
    root.put("budgetYuan", brief.budgetYuan());
    root.put("availableDestinationBudgetYuan", Math.max(0, destinationBudgetYuan));
    root.put("pace", brief.pace());
    root.putPOJO("interests", brief.interests());
    addForecast(root, forecast);
    try {
      return objectMapper.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to serialize general travel planning input", e);
    }
  }

  private String buildModelInput(
      TravelBrief brief,
      TravelForecast forecast,
      List<TravelKnowledgeChunk> attractions,
      int destinationBudgetYuan) {
    ObjectNode root = objectMapper.createObjectNode();
    ObjectNode request = root.putObject("request");
    request.put("destination", brief.destination());
    request.put("days", brief.days());
    request.put("travelers", brief.travelers());
    request.put("budgetYuan", brief.budgetYuan());
    request.put("availableDestinationBudgetYuan", Math.max(0, destinationBudgetYuan));
    request.put("pace", brief.pace());
    request.putPOJO("interests", brief.interests());
    addForecast(root, forecast);
    ArrayNode candidates = root.putArray("candidates");
    for (TravelKnowledgeChunk chunk : attractions) {
      candidates
          .addObject()
          .put("sourceId", chunk.id())
          .put("title", chunk.title())
          .put("outdoor", isOutdoor(chunk))
          .put("evidence", abbreviate(chunk.text(), 180));
    }
    try {
      return objectMapper.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to serialize travel planning input", e);
    }
  }

  private TravelPlan parseModelPlan(
      TravelBrief brief,
      TravelForecast forecast,
      List<TravelKnowledgeChunk> attractions,
      String json) throws Exception {
    JsonNode root = objectMapper.readTree(stripCodeFence(json));
    JsonNode daysNode = root.path("days");
    if (!daysNode.isArray() || daysNode.size() != brief.days()) {
      throw new IllegalArgumentException("Qwen plan day count mismatch");
    }
    Map<String, TravelKnowledgeChunk> byId = new LinkedHashMap<>();
    attractions.forEach(chunk -> byId.put(chunk.id(), chunk));
    Set<String> used = new LinkedHashSet<>();
    List<TravelPlan.DayPlan> days = new ArrayList<>();
    for (int index = 0; index < daysNode.size(); index++) {
      JsonNode dayNode = daysNode.get(index);
      TravelForecast.Daily dailyWeather = forecast.forDay(index + 1);
      JsonNode activitiesNode = dayNode.path("activities");
      if (!activitiesNode.isArray() || activitiesNode.isEmpty()) {
        throw new IllegalArgumentException("Qwen returned an empty travel day");
      }
      List<TravelPlan.Activity> activities = new ArrayList<>();
      for (JsonNode activityNode : activitiesNode) {
        String sourceId = activityNode.path("sourceId").asText();
        TravelKnowledgeChunk chunk = byId.get(sourceId);
        if (chunk == null) throw new IllegalArgumentException("Qwen selected an unknown sourceId");
        if (!used.add(sourceId)) throw new IllegalArgumentException("Qwen repeated an attraction");
        String period = activityNode.path("period").asText(activities.isEmpty() ? "上午" : "下午");
        String reason = activityNode.path("reason").asText("符合本次旅行偏好");
        if (!dailyWeather.available()) reason = hideAssumedClearWeather(reason);
        activities.add(
            new TravelPlan.Activity(
                period,
                chunk.title(),
                chunk.id(),
                isOutdoor(chunk),
                cleanReason(reason)));
      }
      days.add(
          new TravelPlan.DayPlan(
              index + 1,
              dailyWeather.available()
                  ? dayNode.path("theme").asText("城市精选")
                  : hideAssumedClearWeather(dayNode.path("theme").asText("城市精选")),
              dayNode.path("mealSuggestion").asText("建议在当日活动区域就近用餐"),
              activities));
    }
    return new TravelPlan(
        brief,
        forecast,
        days,
        emptyBudget(),
        "qwen-structured-planning",
        executionSteps(brief, true, true),
        0,
        List.of());
  }

  private TravelPlan parseGeneralModelPlan(
      TravelBrief brief, TravelForecast forecast, String json) throws Exception {
    JsonNode root = objectMapper.readTree(stripCodeFence(json));
    JsonNode daysNode = root.path("days");
    if (!daysNode.isArray() || daysNode.size() != brief.days()) {
      throw new IllegalArgumentException("Qwen general plan day count mismatch");
    }
    Set<String> used = new LinkedHashSet<>();
    List<TravelPlan.DayPlan> days = new ArrayList<>();
    for (int index = 0; index < daysNode.size(); index++) {
      JsonNode dayNode = daysNode.get(index);
      TravelForecast.Daily dailyWeather = forecast.forDay(index + 1);
      JsonNode activitiesNode = dayNode.path("activities");
      if (!activitiesNode.isArray() || activitiesNode.isEmpty() || activitiesNode.size() > 3) {
        throw new IllegalArgumentException("Qwen returned an invalid general travel day");
      }
      List<TravelPlan.Activity> activities = new ArrayList<>();
      for (JsonNode activityNode : activitiesNode) {
        String title = activityNode.path("title").asText("").trim();
        if (title.isBlank()) throw new IllegalArgumentException("Qwen returned a blank attraction");
        if (!used.add(title)) throw new IllegalArgumentException("Qwen repeated an attraction");
        String period = activityNode.path("period").asText(activities.isEmpty() ? "上午" : "下午");
        String reason = activityNode.path("reason").asText("符合本次旅行偏好");
        if (!dailyWeather.available()) reason = hideAssumedClearWeather(reason);
        activities.add(
            new TravelPlan.Activity(
                period,
                title,
                "",
                activityNode.path("outdoor").asBoolean(false),
                cleanReason(reason)));
      }
      days.add(
          new TravelPlan.DayPlan(
              index + 1,
              dailyWeather.available()
                  ? dayNode.path("theme").asText("城市精选")
                  : hideAssumedClearWeather(dayNode.path("theme").asText("城市精选")),
              dayNode.path("mealSuggestion").asText("建议在当日活动区域就近用餐"),
              activities));
    }
    return new TravelPlan(
        brief,
        forecast,
        days,
        emptyBudget(),
        "qwen-general-knowledge-planning",
        executionSteps(brief, false, true),
        0,
        List.of());
  }

  private TravelPlan buildLocal(
      TravelBrief brief,
      TravelForecast forecast,
      List<TravelKnowledgeChunk> attractions,
      String generationMode) {
    int perDay = "packed".equals(brief.pace()) ? 3 : 2;
    List<TravelPlan.DayPlan> days = new ArrayList<>();
    int candidateIndex = 0;
    for (int day = 1; day <= brief.days(); day++) {
      List<TravelPlan.Activity> activities = new ArrayList<>();
      for (int slot = 0; slot < perDay && candidateIndex < attractions.size(); slot++) {
        TravelKnowledgeChunk chunk = attractions.get(candidateIndex++);
        activities.add(
            new TravelPlan.Activity(
                slot == 0 ? "上午" : slot == 1 ? "下午（可选）" : "傍晚（可选）",
                chunk.title(),
                chunk.id(),
                isOutdoor(chunk),
                "符合本次旅行的兴趣与节奏，建议结合当天体力灵活安排。"));
      }
      if (activities.isEmpty()) {
        activities.add(
            new TravelPlan.Activity(
                "弹性时段",
                "第" + day + "天城市经典区域自由探索",
                "",
                false,
                "模型服务暂不可用，请结合当地公开信息选择同区域经典项目。"));
      }
      days.add(
          new TravelPlan.DayPlan(
              day,
              day == 1 ? "城市初体验" : "兴趣延伸",
              "建议在当日活动区域就近安排本地餐饮",
              activities));
    }
    return new TravelPlan(
        brief,
        forecast,
        days,
        emptyBudget(),
        generationMode,
        executionSteps(brief, !attractions.isEmpty(), false),
        0,
        List.of());
  }

  static List<String> executionSteps(
      TravelBrief brief, boolean knowledgeGrounded, boolean qwenPlanning) {
    List<String> steps = new ArrayList<>();
    steps.add("规范化旅行目标");
    steps.add("查询当前天气");
    steps.add(
        knowledgeGrounded
            ? "按“" + String.join("、", brief.interests()) + "”检索旅行知识库"
            : "使用模型通用知识筛选城市经典项目");
    if (brief.budgetYuan() / Math.max(1, brief.travelers() * brief.days()) <= 400) {
      steps.add("优先筛选低成本候选");
    }
    if (brief.interests().contains("亲子")) steps.add("匹配亲子友好项目");
    if ("relaxed".equals(brief.pace())) steps.add("降低每日行程强度");
    steps.add(qwenPlanning ? "调用千问生成结构化行程" : "使用本地规则生成兜底行程");
    steps.add("根据实际交通与行程动态核算预算并执行审校");
    return List.copyOf(steps);
  }

  private static TravelPlan.Budget emptyBudget() {
    return new TravelPlan.Budget(0, 0, 0, 0, 0, 0);
  }

  private void addForecast(ObjectNode root, TravelForecast forecast) {
    ArrayNode weather = root.putArray("dailyWeather");
    for (int index = 0; index < forecast.days().size(); index++) {
      TravelForecast.Daily day = forecast.days().get(index);
      ObjectNode item = weather.addObject();
      item.put("day", index + 1);
      item.put("date", day.date().toString());
      item.put("forecastAvailable", day.available());
      item.put("planningCondition", day.available() ? day.condition() : "晴");
      if (day.available()) {
        item.put("minTemperatureC", day.minTemperatureC());
        item.put("maxTemperatureC", day.maxTemperatureC());
        item.put("precipitationProbabilityMax", day.precipitationProbabilityMax());
        item.put("windSpeedMaxKmh", day.windSpeedMaxKmh());
      }
    }
  }

  private static List<TravelKnowledgeChunk> uniqueAttractions(
      List<InMemoryTravelVectorStore.Hit> hits) {
    if (hits == null) return List.of();
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    List<TravelKnowledgeChunk> result = new ArrayList<>();
    for (InMemoryTravelVectorStore.Hit hit : hits) {
      TravelKnowledgeChunk chunk = hit.chunk();
      if ("attraction".equals(chunk.type()) && ids.add(chunk.id())) result.add(chunk);
    }
    return List.copyOf(result);
  }

  private static boolean isOutdoor(TravelKnowledgeChunk chunk) {
    String content = chunk.title() + " " + chunk.text();
    return OUTDOOR_WORDS.stream().anyMatch(content::contains)
        && !content.contains("博物馆")
        && !content.contains("纪念馆");
  }

  private static String cleanReason(String value) {
    if (value == null || value.isBlank()) return "符合本次旅行偏好，可按当天体力灵活安排。";
    String cleaned = value.trim().replaceAll("[；。]+$", "");
    if (cleaned.length() <= 90) return cleaned + "。";
    int boundary = Math.max(cleaned.lastIndexOf('，', 88), cleaned.lastIndexOf('；', 88));
    if (boundary < 35) boundary = 88;
    return cleaned.substring(0, boundary) + "。";
  }

  static String hideAssumedClearWeather(String value) {
    if (value == null || value.isBlank()) return value;
    return value
        .replace("晴朗天气", "常规天气条件")
        .replace("天气晴朗", "天气条件适宜")
        .replace("天气晴好", "天气条件适宜")
        .replace("晴好天气", "常规天气条件")
        .replace("阳光明媚", "天气条件适宜")
        .replace("晴天", "常规天气条件")
        .replace("晴日", "城市");
  }

  private static String abbreviate(String value, int maxLength) {
    if (value == null || value.isBlank()) return "详情请结合官方信息核验";
    return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
  }

  private static String stripCodeFence(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (!trimmed.startsWith("```")) return trimmed;
    int firstLine = trimmed.indexOf('\n');
    int lastFence = trimmed.lastIndexOf("```");
    return firstLine >= 0 && lastFence > firstLine
        ? trimmed.substring(firstLine + 1, lastFence).trim()
        : trimmed;
  }
}
