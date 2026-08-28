package com.github.wechat.ilink.bot.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 根据天气、POI 坐标和铁路时间进行轻量级行程调度。 */
public final class ItineraryScheduler {
  public TravelPlan schedule(TravelPlan plan, TravelMapData mapData) {
    List<TravelPlan.DayPlan> days = clusterByLocation(plan.days(), mapData);
    days = assignIndoorDaysToRain(days, plan.forecast());
    days = applyBoundaryTimes(days, mapData);
    days = renumberAndLabel(days);
    return new TravelPlan(
        plan.brief(),
        plan.forecast(),
        mapData,
        days,
        List.of(),
        plan.budget(),
        plan.generationMode(),
        plan.executionSteps(),
        plan.reviewRounds(),
        plan.reviewIssues());
  }

  static int firstDayMaximum(TravelMapData mapData) {
    int arrival =
        mapData.recommendedOutbound().map(route -> TravelMapData.parseMinutes(route.arrivalTime())).orElse(-1);
    if (arrival < 0) return Integer.MAX_VALUE;
    if (arrival >= 18 * 60) return 1;
    if (arrival >= 14 * 60) return 1;
    if (arrival >= 11 * 60) return 2;
    return 3;
  }

  static int lastDayMaximum(TravelMapData mapData) {
    int departure =
        mapData.recommendedReturn().map(route -> TravelMapData.parseMinutes(route.departureTime())).orElse(-1);
    if (departure < 0) return Integer.MAX_VALUE;
    if (departure <= 10 * 60) return 0;
    if (departure <= 14 * 60) return 1;
    if (departure <= 17 * 60) return 2;
    return 3;
  }

  private static List<TravelPlan.DayPlan> clusterByLocation(
      List<TravelPlan.DayPlan> source, TravelMapData mapData) {
    List<TravelPlan.Activity> all = source.stream().flatMap(day -> day.activities().stream()).toList();
    if (all.size() < 3 || all.stream().anyMatch(activity -> !hasCoordinates(mapData.poi(activity.title())))) {
      return source.stream().map(day -> orderWithinDay(day, mapData)).toList();
    }
    List<TravelPlan.Activity> unused = new ArrayList<>(all);
    List<TravelPlan.DayPlan> result = new ArrayList<>();
    for (TravelPlan.DayPlan template : source) {
      int target = template.activities().size();
      List<TravelPlan.Activity> selected = new ArrayList<>();
      if (target > 0 && !unused.isEmpty()) selected.add(unused.removeFirst());
      while (selected.size() < target && !unused.isEmpty()) {
        TravelPlan.Activity anchor = selected.getLast();
        TravelMapData.PoiSnapshot anchorPoi = mapData.poi(anchor.title());
        TravelPlan.Activity nearest =
            unused.stream()
                .min(Comparator.comparingDouble(value -> distance(anchorPoi, mapData.poi(value.title()))))
                .orElse(unused.getFirst());
        selected.add(nearest);
        unused.remove(nearest);
      }
      result.add(new TravelPlan.DayPlan(template.day(), template.theme(), template.mealSuggestion(), selected));
    }
    return List.copyOf(result);
  }

  private static TravelPlan.DayPlan orderWithinDay(
      TravelPlan.DayPlan day, TravelMapData mapData) {
    if (day.activities().size() < 3) return day;
    List<TravelPlan.Activity> ordered = new ArrayList<>();
    List<TravelPlan.Activity> unused = new ArrayList<>(day.activities());
    ordered.add(unused.removeFirst());
    while (!unused.isEmpty()) {
      TravelMapData.PoiSnapshot anchor = mapData.poi(ordered.getLast().title());
      if (!hasCoordinates(anchor)) {
        ordered.addAll(unused);
        break;
      }
      TravelPlan.Activity nearest =
          unused.stream()
              .filter(value -> hasCoordinates(mapData.poi(value.title())))
              .min(Comparator.comparingDouble(value -> distance(anchor, mapData.poi(value.title()))))
              .orElse(unused.getFirst());
      ordered.add(nearest);
      unused.remove(nearest);
    }
    return new TravelPlan.DayPlan(day.day(), day.theme(), day.mealSuggestion(), ordered);
  }

  private static List<TravelPlan.DayPlan> assignIndoorDaysToRain(
      List<TravelPlan.DayPlan> source, TravelForecast forecast) {
    List<TravelPlan.DayPlan> pool = new ArrayList<>(source);
    List<TravelPlan.DayPlan> assigned = new ArrayList<>(java.util.Collections.nCopies(source.size(), null));
    for (int index = 0; index < source.size(); index++) {
      if (!forecast.forDay(index + 1).rainy()) continue;
      TravelPlan.DayPlan best =
          pool.stream()
              .min(Comparator.comparingLong(ItineraryScheduler::outdoorCount))
              .orElse(pool.getFirst());
      assigned.set(index, best);
      pool.remove(best);
    }
    for (int index = 0; index < assigned.size(); index++) {
      if (assigned.get(index) == null) assigned.set(index, pool.removeFirst());
    }
    return List.copyOf(assigned);
  }

  private static List<TravelPlan.DayPlan> applyBoundaryTimes(
      List<TravelPlan.DayPlan> source, TravelMapData mapData) {
    if (source.isEmpty()) return source;
    List<TravelPlan.DayPlan> result = new ArrayList<>(source);
    result.set(0, trim(result.getFirst(), firstDayMaximum(mapData), true));
    int lastIndex = result.size() - 1;
    result.set(lastIndex, trim(result.get(lastIndex), lastDayMaximum(mapData), false));
    return List.copyOf(result);
  }

  private static TravelPlan.DayPlan trim(
      TravelPlan.DayPlan day, int maximum, boolean keepLaterActivities) {
    if (maximum == Integer.MAX_VALUE || day.activities().size() <= maximum) return day;
    List<TravelPlan.Activity> activities = day.activities();
    List<TravelPlan.Activity> selected;
    if (maximum <= 0) {
      selected = List.of();
    } else if (keepLaterActivities) {
      selected = activities.subList(activities.size() - maximum, activities.size());
    } else {
      selected = activities.subList(0, maximum);
    }
    return new TravelPlan.DayPlan(day.day(), day.theme(), day.mealSuggestion(), selected);
  }

  private static List<TravelPlan.DayPlan> renumberAndLabel(List<TravelPlan.DayPlan> source) {
    List<TravelPlan.DayPlan> result = new ArrayList<>();
    for (int dayIndex = 0; dayIndex < source.size(); dayIndex++) {
      TravelPlan.DayPlan day = source.get(dayIndex);
      List<TravelPlan.Activity> activities = new ArrayList<>();
      for (int index = 0; index < day.activities().size(); index++) {
        TravelPlan.Activity activity = day.activities().get(index);
        activities.add(
            new TravelPlan.Activity(
                period(index, day.activities().size()),
                activity.title(),
                activity.sourceId(),
                activity.outdoor(),
                activity.note()));
      }
      result.add(new TravelPlan.DayPlan(dayIndex + 1, day.theme(), day.mealSuggestion(), activities));
    }
    return List.copyOf(result);
  }

  private static String period(int index, int count) {
    if (count <= 1) return "弹性时段";
    if (count == 2) return index == 0 ? "上午" : "下午或傍晚";
    return switch (index) {
      case 0 -> "上午";
      case 1 -> "下午";
      default -> "傍晚或晚间";
    };
  }

  private static long outdoorCount(TravelPlan.DayPlan day) {
    return day.activities().stream().filter(TravelPlan.Activity::outdoor).count();
  }

  private static boolean hasCoordinates(TravelMapData.PoiSnapshot poi) {
    return poi != null && poi.latitude() != 0 && poi.longitude() != 0;
  }

  private static double distance(
      TravelMapData.PoiSnapshot first, TravelMapData.PoiSnapshot second) {
    if (!hasCoordinates(first) || !hasCoordinates(second)) return Double.MAX_VALUE;
    double dLat = Math.toRadians(second.latitude() - first.latitude());
    double dLon = Math.toRadians(second.longitude() - first.longitude());
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(first.latitude()))
                * Math.cos(Math.toRadians(second.latitude()))
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);
    return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }
}
