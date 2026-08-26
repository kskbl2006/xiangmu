package com.github.wechat.ilink.bot.agent;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Structured forecast aligned one-to-one with the requested itinerary days. */
public record TravelForecast(String place, LocalDate startDate, List<Daily> days) {
  public TravelForecast {
    place = place == null ? "" : place.trim();
    startDate = startDate == null ? LocalDate.now() : startDate;
    days = days == null ? List.of() : List.copyOf(days);
  }

  public Daily forDay(int dayNumber) {
    int index = dayNumber - 1;
    return index >= 0 && index < days.size() ? days.get(index) : Daily.unavailable(startDate.plusDays(index));
  }

  public boolean fullyAvailable() {
    return !days.isEmpty() && days.stream().allMatch(Daily::available);
  }

  public boolean anyAvailable() {
    return days.stream().anyMatch(Daily::available);
  }

  public String toPlanningText() {
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < days.size(); index++) {
      Daily day = days.get(index);
      text.append("第").append(index + 1).append("天（").append(day.date()).append("）：");
      if (day.available()) {
        text.append(day.condition())
            .append("，")
            .append(String.format(Locale.ROOT, "%.1f~%.1f℃", day.minTemperatureC(), day.maxTemperatureC()))
            .append("，最高降水概率").append(day.precipitationProbabilityMax()).append("%")
            .append("，最大风速").append(String.format(Locale.ROOT, "%.1f", day.windSpeedMaxKmh())).append("km/h");
      } else {
        text.append("预报不可查询；内部按晴天条件编排行程，不得向用户声称天气晴朗");
      }
      if (index + 1 < days.size()) text.append('\n');
    }
    return text.toString();
  }

  public static TravelForecast unavailable(String place, LocalDate startDate, int days) {
    return new TravelForecast(
        place,
        startDate,
        java.util.stream.IntStream.range(0, Math.max(1, days))
            .mapToObj(index -> Daily.unavailable(startDate.plusDays(index)))
            .toList());
  }

  public record Daily(
      LocalDate date,
      boolean available,
      String condition,
      double minTemperatureC,
      double maxTemperatureC,
      int precipitationProbabilityMax,
      double windSpeedMaxKmh) {
    public Daily {
      condition = condition == null ? "" : condition.trim();
      precipitationProbabilityMax = Math.max(0, Math.min(100, precipitationProbabilityMax));
    }

    public boolean rainy() {
      return available
          && (precipitationProbabilityMax >= 60
              || condition.contains("雨")
              || condition.contains("雷"));
    }

    public static Daily unavailable(LocalDate date) {
      return new Daily(date, false, "", 0, 0, 0, 0);
    }
  }
}
