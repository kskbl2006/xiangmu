package com.github.wechat.ilink.bot.agent;

import java.time.LocalDate;
import java.util.List;

/** Normalized high-level goal consumed by the travel planning workflow. */
public record TravelBrief(
    String origin,
    String destination,
    int days,
    int travelers,
    int budgetYuan,
    String pace,
    List<String> interests,
    List<String> assumptions,
    LocalDate startDate,
    boolean startDateExplicit) {
  public TravelBrief {
    origin = origin == null ? "" : origin.trim();
    destination = destination == null ? "" : destination.trim();
    days = Math.max(1, Math.min(7, days));
    travelers = Math.max(1, Math.min(20, travelers));
    budgetYuan = Math.max(0, budgetYuan);
    pace = pace == null || pace.isBlank() ? "balanced" : pace;
    interests = interests == null ? List.of() : List.copyOf(interests);
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    startDate = startDate == null ? LocalDate.now() : startDate;
  }

  public TravelBrief(
      String destination,
      int days,
      int travelers,
      int budgetYuan,
      String pace,
      List<String> interests,
      List<String> assumptions) {
    this(
        "",
        destination,
        days,
        travelers,
        budgetYuan,
        pace,
        interests,
        assumptions,
        LocalDate.now(),
        false);
  }

  public TravelBrief(
      String destination,
      int days,
      int travelers,
      int budgetYuan,
      String pace,
      List<String> interests,
      List<String> assumptions,
      LocalDate startDate,
      boolean startDateExplicit) {
    this(
        "",
        destination,
        days,
        travelers,
        budgetYuan,
        pace,
        interests,
        assumptions,
        startDate,
        startDateExplicit);
  }

  public boolean supported() {
    return !destination.isBlank();
  }

  public boolean knowledgeCovered() {
    return List.of("上海", "杭州", "苏州").contains(destination);
  }
}
