package com.github.wechat.ilink.bot.intent;

import java.util.Locale;
import java.util.regex.Pattern;

/** 对命令和天气请求进行首轮规则意图识别。 */
public final class IntentRecognizer {
  public enum Intent {
    WEATHER,
    CLEAR_MEMORY,
    CHAT
  }

  public record Result(Intent intent, String location) {}

  private static final Pattern WEATHER_KEYWORD = Pattern.compile("天气|气温|温度|下雨|降雨");
  private static final Pattern GREETING =
      Pattern.compile("^(?:你好|您好|嗨|哈喽)[，,。！!\\s]*");
  private static final Pattern REQUEST_PREFIX =
      Pattern.compile("^(?:那|那么|请问|帮我|请|麻烦|能否|能不能|可以)?(?:查|查询|看看|看)?(?:一下)?\\s*");
  private static final Pattern TIME_WORD = Pattern.compile("今天|现在|当前|此刻|明天|后天");
  private static final Pattern WEATHER_PHRASE =
      Pattern.compile("(?:的)?(?:天气|气温|温度)(?:怎么样|如何|情况|是多少)?");
  private static final Pattern RAIN_PHRASE =
      Pattern.compile("(?:会不会|会|有)?(?:下雨|降雨)(?:吗|么)?");
  private static final Pattern TRAILING_PARTICLE = Pattern.compile("[呢吗么呀啊吧？?。！!，,\\s]+$");
  private static final Pattern LOCATION_REPLY_PREFIX =
      Pattern.compile("^(?:那|那么)?(?:我在|我住在|位置是|地点是|查|查询|看看)?\\s*");

  public Result recognize(String text) {
    if (text == null || text.isBlank()) {
      return new Result(Intent.CHAT, null);
    }
    String normalized = text.trim().toLowerCase(Locale.ROOT);
    if ("/new".equals(normalized) || "清除记忆".equals(normalized) || "重新开始".equals(normalized)) {
      return new Result(Intent.CLEAR_MEMORY, null);
    }
    if (WEATHER_KEYWORD.matcher(normalized).find()) {
      return new Result(Intent.WEATHER, extractWeatherLocation(normalized));
    }
    return new Result(Intent.CHAT, null);
  }

  /** Bot 询问城市后，从用户回复中提取地点。 */
  public String extractLocationReply(String value) {
    if (value == null) return null;
    String location = LOCATION_REPLY_PREFIX.matcher(value.trim()).replaceFirst("");
    location = TRAILING_PARTICLE.matcher(location).replaceFirst("").trim();
    if (location.length() > 30 || location.contains("天气") || location.contains("？")) {
      return null;
    }
    return location.isBlank() ? null : location;
  }

  private static String extractWeatherLocation(String value) {
    String location = GREETING.matcher(value).replaceFirst("");
    location = REQUEST_PREFIX.matcher(location).replaceFirst("");
    location = TIME_WORD.matcher(location).replaceAll("");
    location = WEATHER_PHRASE.matcher(location).replaceAll("");
    location = RAIN_PHRASE.matcher(location).replaceAll("");
    location = TRAILING_PARTICLE.matcher(location).replaceFirst("").trim();
    return location.isBlank() ? null : location;
  }
}
