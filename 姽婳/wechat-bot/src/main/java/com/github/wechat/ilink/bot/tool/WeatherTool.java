package com.github.wechat.ilink.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.bot.weather.Weather;

/** Function Calling adapter for the existing Seniverse weather module. */
public final class WeatherTool implements BotTool {
  private final Weather weather;
  private final ObjectMapper objectMapper;

  public WeatherTool(Weather weather, ObjectMapper objectMapper) {
    this.weather = weather;
    this.objectMapper = objectMapper;
  }

  @Override
  public String name() {
    return "weather_query";
  }

  @Override
  public String description() {
    return "查询中国城市当前实时天气。用户询问天气、气温或某城市天气时调用。";
  }

  @Override
  public ObjectNode parametersSchema() {
    return ToolSchemas.requiredString(
        "location", "中国城市或包含城市的区县名称，例如：苏州市、成都市、常州市新北区");
  }

  @Override
  public String execute(JsonNode arguments) throws Exception {
    Weather.Report report = weather.current(arguments.path("location").asText());
    ObjectNode result = objectMapper.createObjectNode();
    result.put("location", report.place());
    result.put("condition", report.condition());
    result.put("temperature_c", report.temperatureC());
    result.put("last_updated", report.lastUpdated());
    return objectMapper.writeValueAsString(result);
  }
}
