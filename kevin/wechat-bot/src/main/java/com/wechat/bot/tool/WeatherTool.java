package com.wechat.bot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wechat.bot.config.AppConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 天气查询工具（Function Calling 自定义工具之一）。
 * <p>数据源：Open-Meteo（免费、无需 API Key）。
 * <p>内部为两步链式调用：
 * <ol>
 *   <li>步骤1：城市名 → 经纬度（Geocoding API）</li>
 *   <li>步骤2：经纬度 → 天气预报（Forecast API，依赖步骤1 的返回结果）</li>
 * </ol>
 */
public class WeatherTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    /** WMO 天气编码 → 中文描述 */
    private static final Map<Integer, String> WEATHER_CODES = Map.ofEntries(
            Map.entry(0, "晴"), Map.entry(1, "基本晴"), Map.entry(2, "多云"),
            Map.entry(3, "阴"), Map.entry(45, "雾"), Map.entry(48, "雾凇"),
            Map.entry(51, "小毛毛雨"), Map.entry(53, "毛毛雨"), Map.entry(55, "浓毛毛雨"),
            Map.entry(61, "小雨"), Map.entry(63, "中雨"), Map.entry(65, "大雨"),
            Map.entry(66, "冻雨"), Map.entry(67, "强冻雨"), Map.entry(71, "小雪"),
            Map.entry(73, "中雪"), Map.entry(75, "大雪"), Map.entry(77, "雪粒"),
            Map.entry(80, "小阵雨"), Map.entry(81, "阵雨"), Map.entry(82, "强阵雨"),
            Map.entry(85, "小阵雪"), Map.entry(86, "阵雪"),
            Map.entry(95, "雷暴"), Map.entry(96, "雷暴伴冰雹"), Map.entry(99, "强雷暴伴冰雹"));

    private final AppConfig config;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeatherTool(AppConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String description() {
        return "查询指定城市当前及未来数天的天气情况，包括天气现象、气温、风速等。"
                + "用户询问天气相关问题时调用此工具。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        // JSON Schema 描述函数签名
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", "string");
        city.put("description", "城市名称，支持中文（如：北京、上海）或拼音");

        Map<String, Object> days = new LinkedHashMap<>();
        days.put("type", "integer");
        days.put("description", "预报天数，取值 1-7，默认 1（仅今天）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", city);
        properties.put("days", days);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("city"));
        return schema;
    }

    @Override
    public String execute(JsonNode arguments) throws IOException {
        String city = arguments.path("city").asText("");
        int days = arguments.path("days").asInt(1);
        if (city.isBlank()) {
            return "参数错误：缺少城市名称";
        }
        days = Math.max(1, Math.min(7, days));

        // ---- 步骤1：城市名 → 经纬度（链式调用第一环） ----
        JsonNode location = geocode(city);
        if (location == null) {
            return "未找到城市：「" + city + "」，请确认城市名称是否正确";
        }
        double lat = location.path("latitude").asDouble();
        double lon = location.path("longitude").asDouble();
        String resolvedName = location.path("name").asText(city)
                + (location.path("country").isMissingNode() ? "" : ", " + location.path("country").asText());
        log.info("天气工具步骤1完成：{} -> ({}, {})", city, lat, lon);

        // ---- 步骤2：经纬度 → 天气预报（依赖步骤1 结果） ----
        JsonNode forecast = fetchForecast(lat, lon, days);
        log.info("天气工具步骤2完成：获取到 {} 天预报", days);

        return formatResult(resolvedName, forecast, days);
    }

    /**
     * 步骤1：地理编码，城市名转经纬度。
     */
    private JsonNode geocode(String city) throws IOException {
        String url = config.geocodingUrl()
                + "?name=" + java.net.URLEncoder.encode(city, java.nio.charset.StandardCharsets.UTF_8)
                + "&count=1&language=zh&format=json";
        JsonNode root = getJson(url);
        JsonNode results = root.path("results");
        return results.isArray() && !results.isEmpty() ? results.get(0) : null;
    }

    /**
     * 步骤2：获取天气预报（入参依赖步骤1 的经纬度）。
     */
    private JsonNode fetchForecast(double lat, double lon, int days) throws IOException {
        String url = config.forecastUrl()
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current_weather=true"
                + "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_probability_max,windspeed_10m_max"
                + "&forecast_days=" + days
                + "&timezone=auto";
        return getJson(url);
    }

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + body);
            }
            return mapper.readTree(body);
        }
    }

    private String formatResult(String cityName, JsonNode forecast, int days) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(cityName).append("天气】\n");

        JsonNode current = forecast.path("current_weather");
        if (!current.isMissingNode()) {
            sb.append("当前：")
                    .append(weatherText(current.path("weathercode").asInt()))
                    .append("，气温 ").append(current.path("temperature").asDouble()).append("℃")
                    .append("，风速 ").append(current.path("windspeed").asDouble()).append(" km/h\n");
        }

        JsonNode daily = forecast.path("daily");
        if (!daily.isMissingNode() && !daily.path("time").isEmpty()) {
            sb.append("预报：\n");
            for (int i = 0; i < daily.path("time").size(); i++) {
                String date = daily.path("time").get(i).asText();
                String weather = weatherText(daily.path("weathercode").get(i).asInt());
                double max = daily.path("temperature_2m_max").get(i).asDouble();
                double min = daily.path("temperature_2m_min").get(i).asDouble();
                int rainProb = daily.path("precipitation_probability_max").get(i).path(0).asInt(
                        daily.path("precipitation_probability_max").get(i).asInt(-1));
                sb.append("  ").append(date).append("：").append(weather)
                        .append("，").append(min).append("~").append(max).append("℃");
                if (rainProb >= 0) {
                    sb.append("，降水概率 ").append(rainProb).append("%");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String weatherText(int code) {
        return WEATHER_CODES.getOrDefault(code, "未知天气(" + code + ")");
    }

    /**
     * 供多步链式流程显式调用的入口：城市名 → 结构化天气结果。
     * （BotMessageHandler 的 #chain 演示命令使用）
     */
    public ObjectNode chainExecute(String city) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        JsonNode location = geocode(city);
        if (location == null) {
            result.put("error", "未找到城市 " + city);
            return result;
        }
        JsonNode forecast = fetchForecast(
                location.path("latitude").asDouble(),
                location.path("longitude").asDouble(), 1);
        JsonNode current = forecast.path("current_weather");
        result.put("city", location.path("name").asText(city));
        result.put("weather", weatherText(current.path("weathercode").asInt()));
        result.put("temperature", current.path("temperature").asDouble());
        return result;
    }
}
