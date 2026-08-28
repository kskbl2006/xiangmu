package com.github.wechat.ilink.bot.agent;

import java.util.List;

/** 供规划、审校和最终渲染共享的结构化方案。 */
public record TravelPlan(
    TravelBrief brief,
    TravelForecast forecast,
    TravelMapData mapData,
    List<DayPlan> days,
    List<TravelLeg> cityLegs,
    Budget budget,
    String generationMode,
    List<String> executionSteps,
    int reviewRounds,
    List<ReviewIssue> reviewIssues) {
  public TravelPlan {
    forecast =
        forecast == null
            ? TravelForecast.unavailable(brief.destination(), brief.startDate(), brief.days())
            : forecast;
    mapData = mapData == null ? TravelMapData.disabled(brief.destination()) : mapData;
    days = days == null ? List.of() : List.copyOf(days);
    cityLegs = cityLegs == null ? List.of() : List.copyOf(cityLegs);
    generationMode = generationMode == null ? "local-rule-planning" : generationMode;
    executionSteps = executionSteps == null ? List.of() : List.copyOf(executionSteps);
    reviewIssues = reviewIssues == null ? List.of() : List.copyOf(reviewIssues);
  }

  public TravelPlan(
      TravelBrief brief,
      TravelForecast forecast,
      TravelMapData mapData,
      List<DayPlan> days,
      Budget budget,
      String generationMode,
      List<String> executionSteps,
      int reviewRounds,
      List<ReviewIssue> reviewIssues) {
    this(
        brief,
        forecast,
        mapData,
        days,
        List.of(),
        budget,
        generationMode,
        executionSteps,
        reviewRounds,
        reviewIssues);
  }

  public TravelPlan(
      TravelBrief brief,
      TravelForecast forecast,
      List<DayPlan> days,
      Budget budget,
      String generationMode,
      List<String> executionSteps,
      int reviewRounds,
      List<ReviewIssue> reviewIssues) {
    this(
        brief,
        forecast,
        TravelMapData.disabled(brief.destination()),
        days,
        List.of(),
        budget,
        generationMode,
        executionSteps,
        reviewRounds,
        reviewIssues);
  }

  public TravelPlan(
      TravelBrief brief,
      String weatherSummary,
      List<DayPlan> days,
      Budget budget,
      List<String> executionSteps,
      int reviewRounds,
      List<ReviewIssue> reviewIssues) {
    this(
        brief,
        legacyForecast(brief, weatherSummary),
        TravelMapData.disabled(brief.destination()),
        days,
        List.of(),
        budget,
        "local-rule-planning",
        executionSteps,
        reviewRounds,
        reviewIssues);
  }

  public TravelPlan(
      TravelBrief brief,
      String weatherSummary,
      List<DayPlan> days,
      Budget budget,
      String generationMode,
      List<String> executionSteps,
      int reviewRounds,
      List<ReviewIssue> reviewIssues) {
    this(
        brief,
        legacyForecast(brief, weatherSummary),
        TravelMapData.disabled(brief.destination()),
        days,
        List.of(),
        budget,
        generationMode,
        executionSteps,
        reviewRounds,
        reviewIssues);
  }

  public String weatherSummary() {
    return forecast.toPlanningText();
  }

  private static TravelForecast legacyForecast(TravelBrief brief, String summary) {
    boolean rainy = summary != null && (summary.contains("雨") || summary.contains("雷"));
    String condition = summary == null || summary.isBlank() ? "未知" : summary;
    return new TravelForecast(
        brief.destination(),
        brief.startDate(),
        java.util.stream.IntStream.range(0, brief.days())
            .mapToObj(
                index ->
                    new TravelForecast.Daily(
                        brief.startDate().plusDays(index),
                        true,
                        condition,
                        0,
                        0,
                        rainy ? 80 : 0,
                        0))
            .toList());
  }

  public record DayPlan(int day, String theme, String mealSuggestion, List<Activity> activities) {
    public DayPlan {
      theme = theme == null ? "" : theme;
      mealSuggestion = mealSuggestion == null ? "" : mealSuggestion;
      activities = activities == null ? List.of() : List.copyOf(activities);
    }

    public DayPlan(int day, List<Activity> activities) {
      this(day, "", "", activities);
    }
  }

  public record Activity(
      String period, String title, String sourceId, boolean outdoor, String note) {}

  public record TravelLeg(
      int day,
      String from,
      String to,
      String mode,
      double distanceKm,
      int durationMinutes,
      int costYuan,
      String source,
      double confidence) {
    public TravelLeg {
      day = Math.max(1, day);
      from = from == null ? "" : from.trim();
      to = to == null ? "" : to.trim();
      mode = mode == null || mode.isBlank() ? "待确认" : mode.trim();
      distanceKm = Math.max(0, distanceKm);
      durationMinutes = Math.max(0, durationMinutes);
      costYuan = Math.max(0, costYuan);
      source = source == null || source.isBlank() ? "unknown" : source.trim();
      confidence = Math.max(0, Math.min(1, confidence));
    }
  }

  public record Budget(
      int lodging, int food, int localTransport, int tickets, int buffer, int remaining) {
    public Budget {
      lodging = Math.max(0, lodging);
      food = Math.max(0, food);
      localTransport = Math.max(0, localTransport);
      tickets = Math.max(0, tickets);
      buffer = Math.max(0, buffer);
      remaining = Math.max(0, remaining);
    }

    public Budget(int lodging, int food, int localTransport, int tickets, int buffer) {
      this(lodging, food, localTransport, tickets, buffer, 0);
    }

    public int total() {
      return lodging + food + localTransport + tickets + buffer;
    }
  }

  public enum Severity {
    ERROR,
    WARN,
    INFO
  }

  public record ReviewIssue(
      String code, Severity severity, Integer day, String message, String suggestion) {}
}
