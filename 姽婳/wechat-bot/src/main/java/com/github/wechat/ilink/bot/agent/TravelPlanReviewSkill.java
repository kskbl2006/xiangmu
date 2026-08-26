package com.github.wechat.ilink.bot.agent;

import com.github.wechat.ilink.bot.agent.TravelPlan.Activity;
import com.github.wechat.ilink.bot.agent.TravelPlan.DayPlan;
import com.github.wechat.ilink.bot.agent.TravelPlan.ReviewIssue;
import com.github.wechat.ilink.bot.agent.TravelPlan.Severity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic review and bounded repair implementation for travel-plan-review. */
public final class TravelPlanReviewSkill {
  private final SkillDefinition definition = SkillDefinition.load("travel-plan-review");

  public SkillDefinition definition() {
    return definition;
  }

  public ReviewResult review(TravelPlan plan) {
    List<ReviewIssue> issues = new ArrayList<>();
    if (plan.days().size() != plan.brief().days()) {
      issues.add(error("DAY_COUNT_MISMATCH", null, "行程天数与目标不一致", "补齐或裁剪天数"));
    }
    Set<String> activities = new HashSet<>();
    for (DayPlan day : plan.days()) {
      if (day.activities().isEmpty()) {
        issues.add(error("EMPTY_DAY", day.day(), "当天没有活动", "从未使用候选中补充活动"));
      }
      if (day.activities().size() > 3) {
        issues.add(warn("DAY_OVERLOADED", day.day(), "单日活动超过3个", "移除低优先级项目"));
      }
      long outdoorCount = day.activities().stream().filter(Activity::outdoor).count();
      if (plan.forecast().forDay(day.day()).rainy() && outdoorCount > 1) {
        issues.add(warn("RAIN_OUTDOOR_RISK", day.day(), "雨天安排了多个户外项目", "替换一个室内项目"));
      }
      for (Activity activity : day.activities()) {
        if (!activities.add(activity.title())) {
          issues.add(
              error(
                  "DUPLICATE_ACTIVITY", day.day(), "景点重复：" + activity.title(), "保留首次安排并替换后续项目"));
        }
        TravelMapData.PoiSnapshot poi = plan.mapData().poi(activity.title());
        if (poi != null && looksClosed(poi.openingHours())) {
          issues.add(
              error(
                  "POI_CLOSED",
                  day.day(),
                  "景点可能暂停营业：" + activity.title(),
                  "替换为同区域开放项目"));
        } else if (poi != null && poi.rating() > 0 && poi.rating() < 3.5) {
          issues.add(
              warn(
                  "LOW_POI_RATING",
                  day.day(),
                  "景点地图参考评分偏低：" + activity.title(),
                  "出发前查看近期评价并准备替代项目"));
        }
      }
    }
    int transport = plan.mapData().referenceRoundTripCost(plan.brief().travelers());
    int availableLocalBudget = Math.max(0, plan.brief().budgetYuan() - transport);
    if (plan.budget().total() + transport > plan.brief().budgetYuan()) {
      issues.add(error("BUDGET_EXCEEDED", null, "预计总支出超过用户预算", "压缩目的地支出或调整交通方案"));
    }
    if (transport >= plan.brief().budgetYuan() && transport > 0) {
      issues.add(error("TRANSPORT_BUDGET_EXCEEDED", null, "往返交通参考费用已达到总预算", "提高预算或更换交通方式"));
    }
    int dailyPerPerson =
        plan.brief().budgetYuan()
            / Math.max(1, plan.brief().travelers() * plan.brief().days());
    if (dailyPerPerson < 150) {
      issues.add(
          warn(
              "BUDGET_TOO_LOW",
              null,
              "人均每日预算低于150元，住宿、餐饮和交通可能无法同时满足",
              "提高预算或减少旅行天数"));
    }
    if (plan.budget().buffer() < Math.floor(availableLocalBudget * 0.10)) {
      issues.add(warn("LOW_BUFFER", null, "机动预算低于10%", "将机动预算提高到总预算的10%"));
    }
    boolean passed = issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR);
    return new ReviewResult(passed, List.copyOf(issues));
  }

  /** Repairs deterministic structural errors; caller controls the maximum number of rounds. */
  public TravelPlan repair(TravelPlan plan, List<ReviewIssue> issues) {
    boolean hasDuplicate = hasCode(issues, "DUPLICATE_ACTIVITY");
    boolean hasClosedPoi = hasCode(issues, "POI_CLOSED");
    boolean hasDayCount = hasCode(issues, "DAY_COUNT_MISMATCH");
    boolean hasEmpty = hasCode(issues, "EMPTY_DAY");
    List<DayPlan> repaired = new ArrayList<>();
    Set<String> used = new HashSet<>();
    int targetDays = hasDayCount ? plan.brief().days() : plan.days().size();
    for (int index = 0; index < targetDays; index++) {
      List<Activity> activities = new ArrayList<>();
      if (index < plan.days().size()) {
        for (Activity activity : plan.days().get(index).activities()) {
          TravelMapData.PoiSnapshot poi = plan.mapData().poi(activity.title());
          boolean closed = hasClosedPoi && poi != null && looksClosed(poi.openingHours());
          if (!closed && (!hasDuplicate || used.add(activity.title()))) activities.add(activity);
        }
      }
      if ((hasEmpty || hasDuplicate || hasClosedPoi) && activities.isEmpty()) {
        activities.add(
            new Activity(
                "自由时段",
                "第" + (index + 1) + "天同区域自由探索",
                "fallback:local-exploration:" + (index + 1),
                false,
                "根据体力就近安排；营业状态请出发前核验"));
      }
      String theme = index < plan.days().size() ? plan.days().get(index).theme() : "弹性安排";
      String meal =
          index < plan.days().size()
              ? plan.days().get(index).mealSuggestion()
              : "建议在当日活动区域就近用餐";
      repaired.add(new DayPlan(index + 1, theme, meal, activities));
    }
    TravelPlan.Budget budget =
        hasCode(issues, "BUDGET_EXCEEDED") || hasCode(issues, "LOW_BUFFER")
            ? CnTripPlannerSkill.allocateBudget(
                Math.max(
                    0,
                    plan.brief().budgetYuan()
                        - plan.mapData().referenceRoundTripCost(plan.brief().travelers())))
            : plan.budget();
    return new TravelPlan(
        plan.brief(),
        plan.forecast(),
        plan.mapData(),
        repaired,
        budget,
        plan.generationMode(),
        plan.executionSteps(),
        plan.reviewRounds() + 1,
        issues);
  }

  private static boolean hasCode(List<ReviewIssue> issues, String code) {
    return issues.stream().anyMatch(issue -> code.equals(issue.code()));
  }

  private static boolean looksClosed(String openingHours) {
    if (openingHours == null) return false;
    return openingHours.contains("暂停营业")
        || openingHours.contains("永久关闭")
        || openingHours.contains("停止营业")
        || openingHours.contains("歇业");
  }

  private static ReviewIssue error(String code, Integer day, String message, String suggestion) {
    return new ReviewIssue(code, Severity.ERROR, day, message, suggestion);
  }

  private static ReviewIssue warn(String code, Integer day, String message, String suggestion) {
    return new ReviewIssue(code, Severity.WARN, day, message, suggestion);
  }

  public record ReviewResult(boolean passed, List<ReviewIssue> issues) {}
}
