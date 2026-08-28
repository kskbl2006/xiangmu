package com.github.wechat.ilink.bot.agent;

import java.util.HashSet;
import java.util.Set;

/** 根据旅行信息计算预计支出，并保留可见结余。 */
public final class DynamicBudgetEngine {
  private static final Set<String> PREMIUM_CITIES = Set.of("北京", "上海", "深圳", "广州", "杭州");

  public TravelPlan.Budget calculate(TravelBrief brief, TravelPlan plan, TravelMapData mapData) {
    int cap = brief.budgetYuan();
    int intercity = mapData.referenceRoundTripCost(brief.travelers());
    int available = Math.max(0, cap - intercity);
    int travelers = Math.max(1, brief.travelers());
    int days = Math.max(1, brief.days());
    int nights = Math.max(0, days - 1);
    int rooms = Math.max(1, (travelers + 1) / 2);

    int roomNight = PREMIUM_CITIES.contains(brief.destination()) ? 380 : 300;
    if ("relaxed".equals(brief.pace())) roomNight += 60;
    int lodging = nights * rooms * roomNight;
    int foodPerPersonDay = brief.interests().contains("美食") ? 170 : 130;
    int food = travelers * days * foodPerPersonDay;
    int activityCount = plan.days().stream().mapToInt(day -> day.activities().size()).sum();
    int localTransport =
        plan.cityLegs().isEmpty()
            ? travelers * (days * 20 + Math.max(0, activityCount - days) * 12)
            : plan.cityLegs().stream().mapToInt(TravelPlan.TravelLeg::costYuan).sum();
    int tickets = selectedTicketCost(plan, mapData, travelers);
    int buffer = Math.min(available, Math.max(100, (int) Math.ceil(cap * 0.08)));

    int expected = lodging + food + localTransport + tickets + buffer;
    if (expected > available) {
      // 判断预算不可行前，先切换到经济型基线。
      lodging = nights * rooms * (PREMIUM_CITIES.contains(brief.destination()) ? 260 : 220);
      food = travelers * days * 90;
      localTransport = travelers * (days * 16 + Math.max(0, activityCount - days) * 8);
      buffer = Math.min(Math.max(50, (int) Math.ceil(cap * 0.04)), available);
      expected = lodging + food + localTransport + tickets + buffer;
    }
    int remaining = Math.max(0, available - expected);
    return new TravelPlan.Budget(lodging, food, localTransport, tickets, buffer, remaining);
  }

  private static int selectedTicketCost(TravelPlan plan, TravelMapData mapData, int travelers) {
    Set<String> counted = new HashSet<>();
    double total = 0;
    for (TravelPlan.DayPlan day : plan.days()) {
      for (TravelPlan.Activity activity : day.activities()) {
        if (!counted.add(activity.title())) continue;
        TravelMapData.PoiSnapshot poi = mapData.poi(activity.title());
        if (poi != null && poi.referencePriceYuan() > 0) total += poi.referencePriceYuan();
      }
    }
    return (int) Math.ceil(total * travelers);
  }
}
