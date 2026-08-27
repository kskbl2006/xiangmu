package com.travel.agent.tools;

import com.travel.agent.config.Config;
import com.travel.agent.rag.Kb;
import com.travel.agent.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 景点/美食/酒店检索工具：本地旅行知识库 + RAG 相关性排序。
 *
 * 知识库格式（knowledge/&lt;城市&gt;.md）：
 *   ## 景点
 *   - 名称 | 标签 | 户外|室内 | 评分 | 门票 | 建议时长 | 区域 | 说明
 *   ## 美食
 *   - 名称 | 类型 | 评分 | 人均XX元 | 区域 | 说明
 *   ## 酒店
 *   - 名称 | 档次 | 评分 | XXX元/晚 | 区域 | 说明
 *   ## 提示
 *   - 文本
 *
 * 未覆盖城市自动降级为"通用推荐"数据，保证闭环不中断（Mock 兜底设计）。
 */
public final class PoiTool implements Tool {

    private static final Pattern PRICE = Pattern.compile("(\\d+)");

    private static final Map<String, String> CITY_FILES = Map.of(
            "三亚", "sanya", "上海", "shanghai", "北京", "beijing", "成都", "chengdu",
            "杭州", "hangzhou", "西安", "xian", "重庆", "chongqing", "广州", "guangzhou",
            "南京", "nanjing", "苏州", "suzhou");

    /** 未覆盖城市的通用推荐（名称, 标签, 户外/室内, 评分, 门票, 时长, 区域, 说明）。 */
    private static final String[][] GENERIC_ATTRACTIONS = {
            {"城市中心广场", "地标", "户外", "4.3", "免费", "2小时", "市中心", "地标打卡，感受城市风貌"},
            {"市博物馆", "文化", "室内", "4.5", "免费", "3小时", "市中心", "了解城市历史文化的最佳去处"},
            {"滨水步行街", "街区", "户外", "4.4", "免费", "3小时", "沿江/沿河", "夜景与美食聚集的休闲街区"},
            {"当地美食街", "美食", "户外", "4.3", "免费", "2小时", "老城区", "汇集地方特色小吃"},
            {"科技馆", "亲子", "室内", "4.4", "60元", "3小时", "新区", "互动展项丰富，适合亲子"},
            {"城市公园", "自然", "户外", "4.2", "免费", "2小时", "市区", "本地人休闲首选"},
    };

    private static final Object[][] GENERIC_FOODS = {
            {"本地特色餐厅", "地方菜", 4.4, 80, "市中心", "口碑稳定的本地菜"},
            {"老字号小吃店", "小吃", 4.3, 40, "老城区", "传统小吃"},
            {"连锁简餐", "简餐", 4.2, 50, "商圈", "快捷卫生"},
            {"夜市大排档", "夜市", 4.3, 70, "夜市街", "夜间觅食好去处"},
    };

    private static final Object[][] GENERIC_HOTELS = {
            {"经济连锁酒店", "经济", 4.2, 180, "交通便利处", "性价比之选"},
            {"商务舒适酒店", "舒适", 4.4, 380, "市中心", "位置与品质均衡"},
            {"高端度假酒店", "豪华", 4.6, 880, "景区附近", "设施齐全"},
    };

    @Override
    public String name() {
        return "poi";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> params) {
        String dest = Json.getStr(params, "destination");
        int days = Json.getInt(params, "days");
        List<String> prefs = new ArrayList<>();
        for (Object p : Json.getList(params, "preferences")) {
            prefs.add(Json.str(p));
        }
        String key = "poi:" + dest + ":" + String.join(",", prefs) + ":" + days;
        Map<String, Object> cached = ToolRegistry.cacheGet(key, Config.POI_CACHE_TTL);
        if (cached != null) {
            cached.put("source", "cache");
            return cached;
        }

        Map<String, Object> kb = loadKb(dest);
        String source = "kb";
        if (kb == null) {
            source = "generic";
            kb = genericKb();
        }

        // RAG 排序：需求相关度 + 评分 + 偏好标签命中
        String goal = Json.getStr(params, "goal");
        String query = dest + " " + String.join(" ", prefs) + " " + Json.cut(goal, 30);
        final String q = query;
        final List<String> prefList = prefs;

        List<Map<String, Object>> attractions = new ArrayList<>(Json.getMaps(kb, "attractions"));
        attractions.sort((a, b) -> Double.compare(rankAtt(q, prefList, b), rankAtt(q, prefList, a))); // 相关度+评分+偏好命中，降序

        List<Map<String, Object>> foods = new ArrayList<>(Json.getMaps(kb, "foods"));
        foods.sort((a, b) -> Double.compare(
                Kb.relevance(q, Json.getStr(b, "name") + Json.getStr(b, "type")) * 10 + Json.getDbl(b, "rating") * 2,
                Kb.relevance(q, Json.getStr(a, "name") + Json.getStr(a, "type")) * 10 + Json.getDbl(a, "rating") * 2));

        List<Map<String, Object>> hotels = new ArrayList<>(Json.getMaps(kb, "hotels"));
        hotels.sort((a, b) -> Double.compare(Json.getDbl(b, "rating"), Json.getDbl(a, "rating")));

        // token 压缩：只保留行程编排所需字段，截断长描述
        List<Object> attOut = new ArrayList<>();
        int attLimit = Math.max(days * 2 + 2, 8);
        for (Map<String, Object> a : attractions.subList(0, Math.min(attLimit, attractions.size()))) {
            attOut.add(Json.obj(
                    "name", Json.getStr(a, "name"), "tags", Json.getStr(a, "tags"),
                    "place", Json.getStr(a, "place"), "rating", Json.getDbl(a, "rating"),
                    "ticket", Json.getStr(a, "ticket"), "duration", Json.getStr(a, "duration"),
                    "area", Json.getStr(a, "area"), "desc", Json.cut(Json.getStr(a, "desc"), 30)));
        }
        List<Object> foodOut = new ArrayList<>();
        int foodLimit = Math.max(days * 2, 4);
        for (Map<String, Object> f : foods.subList(0, Math.min(foodLimit, foods.size()))) {
            foodOut.add(Json.obj(
                    "name", Json.getStr(f, "name"), "type", Json.getStr(f, "type"),
                    "rating", Json.getDbl(f, "rating"), "avg_cost", Json.getInt(f, "avg_cost"),
                    "area", Json.getStr(f, "area")));
        }
        List<Object> hotelOut = new ArrayList<>(hotels.subList(0, Math.min(3, hotels.size())));

        Map<String, Object> data = Json.obj(
                "destination", dest,
                "source", source,
                "attractions", attOut,
                "foods", foodOut,
                "hotels", hotelOut,
                "tips", new ArrayList<>(Json.getList(kb, "tips")));
        ToolRegistry.cacheSet(key, data);
        return data;
    }

    private Map<String, Object> genericKb() {
        List<Object> attractions = new ArrayList<>();
        for (String[] a : GENERIC_ATTRACTIONS) {
            attractions.add(Json.obj(
                    "name", a[0], "tags", a[1], "place", a[2], "rating", Double.parseDouble(a[3]),
                    "ticket", a[4], "duration", a[5], "area", a[6], "desc", a[7]));
        }
        List<Object> foods = new ArrayList<>();
        for (Object[] f : GENERIC_FOODS) {
            foods.add(Json.obj("name", f[0], "type", f[1], "rating", f[2],
                    "avg_cost", f[3], "area", f[4], "desc", f[5]));
        }
        List<Object> hotels = new ArrayList<>();
        for (Object[] h : GENERIC_HOTELS) {
            hotels.add(Json.obj("name", h[0], "tier", h[1], "rating", h[2],
                    "price", h[3], "area", h[4], "desc", h[5]));
        }
        return Json.obj(
                "attractions", attractions,
                "foods", foods,
                "hotels", hotels,
                "tips", Json.arr("该城市暂无专属知识库，已使用通用推荐数据（可扩充 knowledge/ 目录）"));
    }

    /** 加载城市知识库：中文文件名 / 拼音文件名 / 按文件头“# 城市：X”匹配。 */
    public static Map<String, Object> loadKb(String destination) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Config.KNOWLEDGE_DIR.resolve(destination + ".md"));
        String f = CITY_FILES.get(destination);
        if (f != null) {
            candidates.add(Config.KNOWLEDGE_DIR.resolve(f + ".md"));
        }
        for (Path p : candidates) {
            if (Files.exists(p)) {
                Map<String, Object> kb = parseKb(p);
                if (kb != null) {
                    return kb;
                }
            }
        }
        // 兜底：按文件头 "# 城市：X" 匹配（新增知识库文件无需改代码）
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Config.KNOWLEDGE_DIR, "*.md")) {
            for (Path p : ds) {
                String first = firstLine(p);
                if (first != null && first.contains(destination)) {
                    return parseKb(p);
                }
            }
        } catch (IOException ignored) {
            // 目录读取失败按无知识库处理
        }
        return null;
    }

    private static String firstLine(Path p) {
        try (var reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return null;
        }
    }

    /** 解析知识库 Markdown（管道分隔的结构化条目）。 */
    private static Map<String, Object> parseKb(Path path) {
        Map<String, Object> kb = Json.obj(
                "attractions", new ArrayList<>(), "foods", new ArrayList<>(),
                "hotels", new ArrayList<>(), "tips", new ArrayList<>());
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        String section = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                String name = line.substring(3).trim();
                section = switch (name) {
                    case "景点" -> "attractions";
                    case "美食" -> "foods";
                    case "酒店" -> "hotels";
                    case "提示" -> "tips";
                    default -> null;
                };
                continue;
            }
            if (!line.startsWith("- ") || section == null) {
                continue;
            }
            String body = line.substring(2);
            String[] parts = body.split("\\|");
            List<String> ps = new ArrayList<>();
            for (String p : parts) {
                ps.add(p.trim());
            }
            switch (section) {
                case "attractions" -> {
                    if (ps.size() >= 8) {
                        Json.rawMaps(kb, "attractions").add(Json.obj(
                                "name", ps.get(0), "tags", ps.get(1), "place", ps.get(2),
                                "rating", Double.parseDouble(ps.get(3)), "ticket", ps.get(4),
                                "duration", ps.get(5), "area", ps.get(6), "desc", ps.get(7)));
                    }
                }
                case "foods" -> {
                    if (ps.size() >= 6) {
                        Json.rawMaps(kb, "foods").add(Json.obj(
                                "name", ps.get(0), "type", ps.get(1),
                                "rating", Double.parseDouble(ps.get(2)),
                                "avg_cost", price(ps.get(3)), "area", ps.get(4), "desc", ps.get(5)));
                    }
                }
                case "hotels" -> {
                    if (ps.size() >= 6) {
                        Json.rawMaps(kb, "hotels").add(Json.obj(
                                "name", ps.get(0), "tier", ps.get(1),
                                "rating", Double.parseDouble(ps.get(2)),
                                "price", price(ps.get(3)), "area", ps.get(4), "desc", ps.get(5)));
                    }
                }
                case "tips" -> Json.getList(kb, "tips").add(body.trim());
                default -> {
                }
            }
        }
        return Json.rawMaps(kb, "attractions").isEmpty() ? null : kb;
    }

    /** 从文本中抽取第一个数字（"人均150元" → 150）。 */
    private static int price(String text) {
        Matcher m = PRICE.matcher(String.valueOf(text));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 门票文本 → 数字（"144元" → 144，"免费" → 0）。 */
    public static int ticketNum(String ticket) {
        return price(ticket);
    }

    /** 景点综合排序分：相关度 ×10 + 评分 ×2 + 偏好标签命中 +4。 */
    private static double rankAtt(String query, List<String> prefs, Map<String, Object> a) {
        double score = Kb.relevance(query, Json.getStr(a, "name") + Json.getStr(a, "tags") + Json.getStr(a, "desc")) * 10
                + Json.getDbl(a, "rating") * 2;
        String tags = Json.getStr(a, "tags");
        for (String p : prefs) {
            if (tags.contains(p)) {
                score += 4;
                break;
            }
        }
        return score;
    }
}
