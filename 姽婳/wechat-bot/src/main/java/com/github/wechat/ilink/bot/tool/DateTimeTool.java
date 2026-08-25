package com.github.wechat.ilink.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Returns current China time and supports relative date calculations. */
public final class DateTimeTool implements BotTool {
  private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final Map<DayOfWeek, String> WEEKDAYS =
      Map.of(
          DayOfWeek.MONDAY, "星期一",
          DayOfWeek.TUESDAY, "星期二",
          DayOfWeek.WEDNESDAY, "星期三",
          DayOfWeek.THURSDAY, "星期四",
          DayOfWeek.FRIDAY, "星期五",
          DayOfWeek.SATURDAY, "星期六",
          DayOfWeek.SUNDAY, "星期日");

  private final ObjectMapper objectMapper;
  private final Clock clock;

  public DateTimeTool(ObjectMapper objectMapper) {
    this(objectMapper, Clock.system(CHINA_ZONE));
  }

  DateTimeTool(ObjectMapper objectMapper, Clock clock) {
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "datetime_query";
  }

  @Override
  public String description() {
    return "获取中国标准时间、日期和星期，也可计算昨天、明天或指定天数前后的日期。";
  }

  @Override
  public ObjectNode parametersSchema() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    ObjectNode offset = schema.putObject("properties").putObject("offset_days");
    offset.put("type", "integer");
    offset.put("description", "相对今天的天数；今天为0、明天为1、昨天为-1，默认0");
    return schema;
  }

  @Override
  public String execute(JsonNode arguments) throws Exception {
    int offsetDays = arguments.path("offset_days").asInt(0);
    if (Math.abs((long) offsetDays) > 36_500L) {
      throw new IllegalArgumentException("offset_days must be between -36500 and 36500");
    }
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDate targetDate = now.toLocalDate().plusDays(offsetDays);
    ObjectNode result = objectMapper.createObjectNode();
    result.put("date", targetDate.format(DATE_FORMAT));
    result.put("time", now.format(TIME_FORMAT));
    result.put("weekday", WEEKDAYS.get(targetDate.getDayOfWeek()));
    result.put("relative_label", relativeLabel(offsetDays));
    result.put("offset_days", offsetDays);
    result.put("timezone", CHINA_ZONE.getId());
    return objectMapper.writeValueAsString(result);
  }

  private static String relativeLabel(int offsetDays) {
    return switch (offsetDays) {
      case -1 -> "昨天";
      case 0 -> "今天";
      case 1 -> "明天";
      default -> offsetDays > 0 ? offsetDays + "天后" : -offsetDays + "天前";
    };
  }
}
