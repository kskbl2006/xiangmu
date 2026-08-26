package com.github.wechat.ilink.bot.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.agent.TravelForecast;
import com.github.wechat.ilink.bot.config.AppConfig;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OpenMeteoWeatherTest {
  @Test
  void alignsDailyForecastToTripDatesAndMarksMissingDaysUnavailable() throws Exception {
    String json =
        """
        {
          "daily": {
            "time": ["2026-08-25", "2026-08-26", "2026-08-27"],
            "weather_code": [1, 63, 3],
            "temperature_2m_max": [31.0, 27.0, 29.0],
            "temperature_2m_min": [23.0, 21.0, 22.0],
            "precipitation_probability_max": [10, 80, 20],
            "wind_speed_10m_max": [8.0, 16.0, 10.0]
          }
        }
        """;
    OpenMeteoWeather provider = new OpenMeteoWeather(AppConfig.fromEnvironment());

    TravelForecast forecast =
        provider.parseForecast(json, "北京", LocalDate.of(2026, 8, 26), 3);

    assertEquals(LocalDate.of(2026, 8, 26), forecast.days().get(0).date());
    assertEquals("中雨", forecast.days().get(0).condition());
    assertTrue(forecast.days().get(0).rainy());
    assertTrue(forecast.days().get(1).available());
    assertFalse(forecast.days().get(2).available());
    assertFalse(forecast.fullyAvailable());
  }
}
