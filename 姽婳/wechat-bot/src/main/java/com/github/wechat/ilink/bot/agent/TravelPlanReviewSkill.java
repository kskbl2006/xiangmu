package com.github.wechat.ilink.bot.agent;

import com.github.wechat.ilink.bot.agent.TravelPlan.Activity;
import com.github.wechat.ilink.bot.agent.TravelPlan.DayPlan;
import com.github.wechat.ilink.bot.agent.TravelPlan.ReviewIssue;
import com.github.wechat.ilink.bot.agent.TravelPlan.Severity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 旅行方案的规则审校与有限轮次修复。 */
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
      boolean transportOnlyDay =
          (day.day() == 1 && ItineraryScheduler.firstDayMaximum(plan.mapData()) == 0)
              || (day.day() == plan.days().size()
                  && ItineraryScheduler.lastDayMaximum(plan.mapData()) == 0);
      if (day.activities().isEmpty() && !transportOnlyDay) {
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
        java.time.LocalDate visitDate = plan.brief().startDate().plusDays(day.day() - 1L);
        if (poi != null && looksClosedOnDate(poi.openingHours(), visitDate)) {
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
    if (!plan.days().isEmpty()
        && plan.days().getFirst().activities().size()
            > ItineraryScheduler.firstDayMaximum(plan.mapData())) {
      issues.add(error("FIRST_DAY_OVERLOADED", 1, "首日活动数量与抵达时间冲突", "按推荐去程到达时间压缩首日行程"));
    }
    if (!plan.days().isEmpty()
        && plan.days().getLast().activities().size()
            > ItineraryScheduler.lastDayMaximum(plan.mapData())) {
      issues.add(
          error(
              "LAST_DAY_OVERLOADED",
              plan.days().size(),
              "末日活动数量与返程时间冲突",
              "按推荐返程出发时间压缩末日行程"));
    }
    for (TravelPlan.TravelLeg leg : plan.cityLegs()) {
      if (leg.distanceKm() > 25) {
        issues.add(
            warn(
                "LONG_CITY_LEG",
                leg.day(),
                "相邻地点跨度较大：" + leg.from() + "至" + leg.to(),
                "确认路线后考虑调整为同区域景点"));
      }
    }
    if (!plan.brief().origin().isBlank()
        && !plan.brief().origin().equals(plan.brief().destination())
        && (plan.mapData().outboundRoutes().isEmpty() || plan.mapData().returnRoutes().isEmpty())) {
      issues.add(
          warn(
              "INTERCITY_ROUTE_INCOMPLETE",
              null,
              "往返交通候选不完整",
              "通过12306或地图官方入口确认班次后再出发"));
    } else if ((!plan.mapData().outboundRoutes().isEmpty() || !plan.mapData().returnRoutes().isEmpty())
        && plan.mapData().referenceRoundTripCost(plan.brief().travelers()) == 0) {
      issues.add(
          warn(
              "INTERCITY_PRICE_UNKNOWN",
              null,
              "往返交通参考费用不完整",
              "未知费用尚未计入预算，请在官方平台核价"));
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

  /** 修复明确的结构错误，最大轮次由调用方控制。 */
  public TravelPlan repair(TravelPlan plan, List<ReviewIssue> issues) {
    boolean hasDuplicate = hasCode(issues, "DUPLICATE_ACTIVITY");
    boolean hasClosedPoi = hasCode(issues, "POI_CLOSED");
    boolean hasDayCount = hasCode(issues, "DAY_COUNT_MISMATCH");
    boolean hasEmpty = hasCode(issues, "EMPTY_DAY");
    boolean hasBudgetExceeded = hasCode(issues, "BUDGET_EXCEEDED");
    List<DayPlan> repaired = new ArrayList<>();
    Set<String> used = new HashSet<>();
    int targetDays = hasDayCount ? plan.brief().days() : plan.days().size();
    for (int index = 0; index < targetDays; index++) {
      List<Activity> activities = new ArrayList<>();
      if (index < plan.days().size()) {
        for (Activity activity : plan.days().get(index).activities()) {
          TravelMapData.PoiSnapshot poi = plan.mapData().poi(activity.title());
          java.time.LocalDate visitDate = plan.brief().startDate().plusDays(index);
          boolean closed =
              hasClosedPoi && poi != null && looksClosedOnDate(poi.openingHours(), visitDate);
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
    if (hasBudgetExceeded) repaired = removeMostExpensiveOptionalActivity(repaired, plan.mapData());
    return new TravelPlan(
        plan.brief(),
        plan.forecast(),
        plan.mapData(),
        repaired,
        plan.budget(),
        plan.generationMode(),
        plan.executionSteps(),
        plan.reviewRounds() + 1,
        issues);
  }

  private static List<DayPlan> removeMostExpensiveOptionalActivity(
      List<DayPlan> days, TravelMapData mapData) {
    int selectedDay = -1;
    int selectedActivity = -1;
    double highestPrice = 0;
    for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
      DayPlan day = days.get(dayIndex);
      if (day.activities().size() <= 1) continue;
      for (int activityIndex = 0; activityIndex < day.activities().size(); activityIndex++) {
        TravelMapData.PoiSnapshot poi = mapData.poi(day.activities().get(activityIndex).title());
        double price = poi == null ? 0 : poi.referencePriceYuan();
        if (price > highestPrice) {
          highestPrice = price;
          selectedDay = dayIndex;
          selectedActivity = activityIndex;
        }
      }
    }
    if (selectedDay < 0) return days;
    List<DayPlan> result = new ArrayList<>(days);
    DayPlan day = result.get(selectedDay);
    List<Activity> activities = new ArrayList<>(day.activities());
    activities.remove(selectedActivity);
    result.set(selectedDay, new DayPlan(day.day(), day.theme(), day.mealSuggestion(), activities));
    return List.copyOf(result);
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

  private static boolean looksClosedOnDate(
      String openingHours, java.time.LocalDate visitDate) {
    if (looksClosed(openingHours)) return true;
    if (openingHours == null || openingHours.isBlank() || visitDate == null) return false;
    String weekday =
        switch (visitDate.getDayOfWeek()) {
          case MONDAY -> "周一";
          case TUESDAY -> "周二";
          case WEDNESDAY -> "周三";
          case THURSDAY -> "周四";
          case FRIDAY -> "周五";
          case SATURDAY -> "周六";
          case SUNDAY -> "周日";
        };
    return openingHours.contains(weekday + "闭馆")
        || openingHours.contains(weekday + "不开放")
        || openingHours.contains(weekday + "休息");
  }

  private static ReviewIssue error(String code, Integer day, String message, String suggestion) {
    return new ReviewIssue(code, Severity.ERROR, day, message, suggestion);
  }

  private static ReviewIssue warn(String code, Integer day, String message, String suggestion) {
    return new ReviewIssue(code, Severity.WARN, day, message, suggestion);
  }

  public record ReviewResult(boolean passed, List<ReviewIssue> issues) {}
}
