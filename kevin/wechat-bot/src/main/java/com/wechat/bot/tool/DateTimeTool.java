package com.wechat.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 日期时间工具（Function Calling 自定义工具之二）。
 * 用户询问"现在几点""今天星期几""明天是几号"等时间问题时由 LLM 调用。
 */
public class DateTimeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DateTimeTool.class);

    private static final Map<String, String> WEEK_CN = Map.of(
            "MONDAY", "星期一", "TUESDAY", "星期二", "WEDNESDAY", "星期三",
            "THURSDAY", "星期四", "FRIDAY", "星期五", "SATURDAY", "星期六",
            "SUNDAY", "星期日");

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public String name() {
        return "get_datetime";
    }

    @Override
    public String description() {
        return "获取当前日期、时间、星期等信息，也可以计算指定天数之后的日期。"
                + "用户询问时间、日期、星期相关问题时调用此工具。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> offsetDays = new LinkedHashMap<>();
        offsetDays.put("type", "integer");
        offsetDays.put("description", "相对今天的天数偏移，如 0 表示今天、1 表示明天、-1 表示昨天，默认 0");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("offsetDays", offsetDays);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    @Override
    public String execute(JsonNode arguments) {
        int offsetDays = arguments.path("offsetDays").asInt(0);
        LocalDateTime now = LocalDateTime.now();
        LocalDate date = now.toLocalDate().plusDays(offsetDays);

        String prefix = offsetDays == 0 ? "今天" : offsetDays == 1 ? "明天"
                : offsetDays == -1 ? "昨天" : (offsetDays > 0 ? offsetDays + " 天后" : -offsetDays + " 天前");

        log.info("日期时间工具被调用，offsetDays={}", offsetDays);
        return String.format("%s是 %s（%s），当前时间 %s",
                prefix,
                date.format(DATE_FMT),
                weekCn(date.getDayOfWeek()),
                now.format(TIME_FMT));
    }

    private String weekCn(DayOfWeek day) {
        return WEEK_CN.getOrDefault(day.name(), day.toString());
    }
}
