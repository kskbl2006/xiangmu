package com.github.wechat.ilink.bot.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wechat.ilink.bot.config.AppConfig;
import org.junit.jupiter.api.Test;

class WeatherTest {
  @Test
  void createsCityFallbackCandidatesForChineseDistrict() {
    assertEquals(
        java.util.List.of("常州市", "常州", "常州市新北区"),
        Weather.locationCandidates("常州市新北区").stream().toList());
  }

  @Test
  void parsesSeniverseCurrentConditions() throws Exception {
    String json =
        """
        {
          "results": [{
            "location": {"name":"宣城", "path":"宣城,宣城,安徽,中国"},
            "now": {"text":"晴", "temperature":"31"},
            "last_update":"2026-08-24T18:10:00+08:00"
          }]
        }
        """;
    Weather.Report report =
        new Weather(AppConfig.fromEnvironment()).parseReport(json, "宣城市");
    assertEquals("安徽 宣城", report.place());
    assertEquals("晴", report.condition());
    assertTrue(report.toChineseText().contains("31.0℃"));
    assertTrue(!report.toChineseText().contains("湿度"));
  }
}
