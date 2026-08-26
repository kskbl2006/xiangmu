package com.github.wechat.ilink.bot.agent;

import java.util.List;

/** Structured artifact shared by the planner, reviewer and final renderer. */
public record TravelPlan(
    TravelBrief brief,
    TravelForecast forecast,
    TravelMapData mapData,
    List<DayPlan> days,
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
    generationMode = generationMode == null ? "local-rule-planning" : generationMode;
    executionSteps = executionSteps == null ? List.of() : List.copyOf(executionSteps);
    reviewIssues = reviewIssues == null ? List.of() : List.copyOf(reviewIssues);
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

  public record Budget(int lodging, int food, int localTransport, int tickets, int buffer) {
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
