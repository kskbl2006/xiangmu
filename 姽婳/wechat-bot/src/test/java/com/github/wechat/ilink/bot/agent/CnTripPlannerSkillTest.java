package com.github.wechat.ilink.bot.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CnTripPlannerSkillTest {
  @Test
  void doesNotExposeAssumedClearWeatherWhenForecastIsUnavailable() {
    String cleaned = CnTripPlannerSkill.hideAssumedClearWeather("晴天适宜户外漫步，天气晴好可看夜景");

    assertFalse(cleaned.contains("晴"));
    assertTrue(cleaned.contains("常规天气条件"));
  }
}
