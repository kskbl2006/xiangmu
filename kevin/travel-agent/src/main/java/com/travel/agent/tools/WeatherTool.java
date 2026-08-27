package com.travel.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.config.Config;
import com.travel.agent.util.Json;
import com.travel.agent.util.Log;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 天气查询工具：Open-Meteo 真实 API（免密钥）→ 失败/超范围自动降级 Mock（确定性模拟）。
 */
public final class WeatherTool implements Tool {

    /** 城市坐标（供天气 API 与预算的距离测算共用）。 */
    public static final Map<String, double[]> CITY_COORDS = Map.ofEntries(
            Map.entry("三亚", new double[]{18.25, 109.50}), Map.entry("上海", new double[]{31.23, 121.47}),
            Map.entry("北京", new double[]{39.90, 116.40}), Map.entry("成都", new double[]{30.57, 104.07}),
            Map.entry("杭州", new double[]{30.27, 120.16}), Map.entry("西安", new double[]{34.34, 108.94}),
            Map.entry("重庆", new double[]{29.56, 106.55}), Map.entry("广州", new double[]{23.13, 113.26}),
            Map.entry("南京", new double[]{32.04, 118.78}), Map.entry("苏州", new double[]{31.30, 120.58}));

    private static final Map<String, int[]> CITY_TEMP_BASE = Map.ofEntries(
            Map.entry("三亚", new int[]{26, 32}), Map.entry("上海", new int[]{20, 28}),
            Map.entry("北京", new int[]{18, 30}), Map.entry("成都", new int[]{19, 27}),
            Map.entry("杭州", new int[]{20, 29}), Map.entry("西安", new int[]{16, 30}),
            Map.entry("重庆", new int[]{24, 34}), Map.entry("广州", new int[]{25, 33}),
            Map.entry("南京", new int[]{20, 31}), Map.entry("苏州", new int[]{20, 31}));

    private static final Map<Integer, String> WMO_CODE = Map.ofEntries(
            Map.entry(0, "晴"), Map.entry(1, "晴"), Map.entry(2, "多云"), Map.entry(3, "多云"),
            Map.entry(45, "雾"), Map.entry(48, "雾"), Map.entry(51, "小雨"), Map.entry(53, "小雨"),
            Map.entry(55, "小雨"), Map.entry(61, "小雨"), Map.entry(63, "中雨"), Map.entry(65, "大雨"),
            Map.entry(71, "小雪"), Map.entry(73, "小雪"), Map.entry(75, "大雪"), Map.entry(80, "阵雨"),
            Map.entry(81, "阵雨"), Map.entry(82, "阵雨"), Map.entry(95, "雷雨"));

    private static final Map<String, String> TIPS = Map.ofEntries(
            Map.entry("晴", "紫外线较强，注意防晒补水"),
            Map.entry("多云", "体感舒适，适合户外活动"),
            Map.entry("雾", "能见度低，出行注意交通安全"),
            Map.entry("小雨", "携带雨具，建议优先安排室内景点"),
            Map.entry("中雨", "雨势较大，建议以室内活动为主"),
            Map.entry("大雨", "不建议户外行程，请灵活调整安排"),
            Map.entry("阵雨", "备好雨具，行程中预留弹性时间"),
            Map.entry("雷雨", "避免户外与水上项目，注意安全"),
            Map.entry("小雪", "注意保暖防滑"),
            Map.entry("大雪", "关注交通管制与航班动态"));

    private static final String WEEK_CN = "一二三四五六日";

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> params) {
        String dest = Json.getStr(params, "destination");
        String start = Json.getStr(params, "start_date");
        int days = Json.getInt(params, "days");
        String key = "weather:" + dest + ":" + start + ":" + days;
        if (!Json.getBool(params, "force_refresh")) {
            Map<String, Object> cached = ToolRegistry.cacheGet(key, Config.WEATHER_CACHE_TTL);
            if (cached != null) {
                cached.put("source", "cache");
                return cached;
            }
        }
        Map<String, Object> data = real(dest, start, days);
        if (data == null) {
            data = mock(dest, start, days);
            data.put("source", "mock");
        } else {
            data.put("source", "open-meteo");
        }
        ToolRegistry.cacheSet(key, data);
        return data;
    }

    // ---- 真实 API ----
    private Map<String, Object> real(String dest, String start, int days) {
        double[] coords = CITY_COORDS.get(dest);
        if (coords == null) {
            return null;
        }
        LocalDate startDate;
        try {
            startDate = LocalDate.parse(start);
        } catch (Exception e) {
            return null;
        }
        long offset = ChronoUnit.DAYS.between(LocalDate.now(), startDate);
        if (offset < 0 || offset + days > 15) {   // 预报仅支持 16 天内
            return null;
        }
        try {
            String q = "latitude=" + coords[0] + "&longitude=" + coords[1]
                    + "&daily=" + URLEncoder.encode(
                            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                            StandardCharsets.UTF_8)
                    + "&forecast_days=" + Math.min(offset + days, 16) + "&timezone=auto";
            java.net.http.HttpRequest req = java.net.http.HttpRequest
                    .newBuilder(URI.create("https://api.open-meteo.com/v1/forecast?" + q))
                    .timeout(java.time.Duration.ofSeconds(Config.TOOL_TIMEOUT))
                    .GET().build();
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            JsonNode daily = Json.MAPPER.readTree(resp.body()).path("daily");
            List<Object> out = new ArrayList<>();
            for (int i = (int) offset; i < offset + days; i++) {
                LocalDate d = startDate.plusDays(i - offset);
                int code = daily.path("weather_code").path(i).asInt(3);
                String cond = WMO_CODE.getOrDefault(code, "多云");
                JsonNode rainNode = daily.path("precipitation_probability_max").path(i);
                int rain = rainNode.isNull() ? 0 : rainNode.asInt(0);
                out.add(day(d, cond,
                        (int) Math.round(daily.path("temperature_2m_min").path(i).asDouble()),
                        (int) Math.round(daily.path("temperature_2m_max").path(i).asDouble()),
                        rain));
            }
            return Json.obj("days", out);
        } catch (Exception e) {
            Log.info("WEATHER", "真实天气 API 失败，降级 Mock：" + e.getMessage());
            return null;
        }
    }

    // ---- Mock 兜底（确定性：同城市同日期结果一致，便于测试与演示）----
    private Map<String, Object> mock(String dest, String start, int days) {
        int[] base = CITY_TEMP_BASE.getOrDefault(dest, new int[]{20, 28});
        int lo = base[0];
        int hi = base[1];
        Random rng = new Random((dest + "-" + start).hashCode());
        String[] conds = {"晴", "晴", "多云", "多云", "小雨", "阵雨", "雷雨"};
        Map<String, Integer> rainByCond = Map.of("晴", 5, "多云", 15, "小雨", 75,
                "阵雨", 60, "雷雨", 85);
        LocalDate startDate = LocalDate.parse(start);
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = startDate.plusDays(i);
            String cond = conds[rng.nextInt(conds.length)];
            out.add(day(d, cond, lo + rng.nextInt(4) - 2, hi + rng.nextInt(4) - 1,
                    rainByCond.getOrDefault(cond, 20)));
        }
        return Json.obj("days", out);
    }

    private Map<String, Object> day(LocalDate d, String cond, int tempLo, int tempHi, int rainProb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.toString());
        m.put("weekday", "周" + WEEK_CN.charAt(d.getDayOfWeek().getValue() - 1));
        m.put("cond", cond);
        m.put("temp_lo", tempLo);
        m.put("temp_hi", tempHi);
        m.put("rain_prob", rainProb);
        m.put("tip", TIPS.getOrDefault(cond, ""));
        return m;
    }
}
