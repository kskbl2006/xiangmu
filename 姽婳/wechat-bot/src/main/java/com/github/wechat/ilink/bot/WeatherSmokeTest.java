package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.config.AppConfig;
import com.github.wechat.ilink.bot.weather.Weather;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 独立测试心知天气，不连接微信。 */
public final class WeatherSmokeTest {
  private static final Logger log = LoggerFactory.getLogger(WeatherSmokeTest.class);

  private WeatherSmokeTest() {}

  public static void main(String[] args) throws Exception {
    String location = args.length == 0 ? "北京" : args[0];
    Weather.Report report = new Weather(AppConfig.fromEnvironment()).current(location);
    log.info("Weather smoke test succeeded: {}", report.toChineseText());
  }
}
