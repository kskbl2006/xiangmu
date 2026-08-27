package com.travel.agent.tools;

import com.travel.agent.config.Config;
import com.travel.agent.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 行程编排工具：逐日时间槽 + 天气感知（雨天优先室内）+ 餐饮轮换。 */
public final class ItineraryTool implements Tool {

    @Override
    public String name() {
        return "itinerary";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> params) {
        Map<String, Object> req = Json.getMap(params, "request");
        Map<String, Object> weather = Json.getMap(params, "weather");
        Map<String, Object> poi = Json.getMap(params, "poi");
        Map<String, Object> budget = Json.getMap(params, "budget");

        int people = Json.getInt(req, "people");
        // token 压缩：只取排序后的前 N 个候选进入编排
        List<Map<String, Object>> poolAll = Json.getMaps(poi, "attractions");
        int poolSize = Math.min(Config.MAX_POI_IN_ITINERARY_STEP, poolAll.size());
        List<Map<String, Object>> pool = new ArrayList<>(poolAll.subList(0, poolSize));
        Set<String> used = new HashSet<>();
        List<Map<String, Object>> foods = Json.getMaps(poi, "foods");
        List<Object> daysOut = new ArrayList<>();
        int[] mealI = {0};   // 全局轮换指针（同区域优先时避免连吃同一家）

        List<Map<String, Object>> weatherDays = Json.getMaps(weather, "days");
        for (int i = 0; i < weatherDays.size(); i++) {
            Map<String, Object> wday = weatherDays.get(i);
            boolean rainy = isRainy(wday);
            List<Object> items = new ArrayList<>();
            String[] dayArea = {null};

            // 上/下午各安排 1 个景点；不雨天且有余量时加夜间活动
            // 同区域就近：当日首个景点确定片区，后续优先安排同片区景点与餐厅
            List<Object[]> slots = new ArrayList<>(List.of(
                    new Object[]{"09:00", "上午"}, new Object[]{"14:00", "下午"}));
            if (!rainy) {
                slots.add(new Object[]{"20:00", "夜游"});
            }
            for (Object[] slot : slots) {
                String time = (String) slot[0];
                String label = (String) slot[1];
                Map<String, Object> pick = pick(pool, used, rainy, dayArea[0]);
                if (pick == null) {
                    break;
                }
                used.add(Json.getStr(pick, "name"));
                if (dayArea[0] == null) {
                    dayArea[0] = Json.getStr(pick, "area");
                }
                int cost = PoiTool.ticketNum(Json.getStr(pick, "ticket")) * people;
                String note = String.format("%s · %s · 评分%.1f", Json.getStr(pick, "duration"),
                        Json.getStr(pick, "place"), Json.getDbl(pick, "rating"));
                if (rainy && "室内".equals(Json.getStr(pick, "place"))) {
                    note += "（雨天安排）";
                }
                items.add(item(time, label, "景点", Json.getStr(pick, "name"), cost, note));
            }

            // 午晚餐：优先当日同片区餐厅，全局轮换防重复
            if (!foods.isEmpty()) {
                Map<String, Object> lunch = pickMeal(foods, dayArea[0], mealI);
                Map<String, Object> dinner = pickMeal(foods, dayArea[0], mealI);
                items.add(item("12:00", "午餐", "美食", Json.getStr(lunch, "name"),
                        Json.getInt(lunch, "avg_cost") * people,
                        String.format("人均%d元 · %s", Json.getInt(lunch, "avg_cost"), Json.getStr(lunch, "area"))));
                items.add(item("18:00", "晚餐", "美食", Json.getStr(dinner, "name"),
                        Json.getInt(dinner, "avg_cost") * people,
                        String.format("人均%d元 · %s", Json.getInt(dinner, "avg_cost"), Json.getStr(dinner, "area"))));
            }

            items.sort(Comparator.comparing(o -> Json.getStr((Map<String, Object>) o, "time")));
            int dayCost = 0;
            for (Object it : items) {
                dayCost += Json.getInt((Map<String, Object>) it, "cost");
            }
            daysOut.add(Json.obj(
                    "index", i + 1,
                    "date", Json.getStr(wday, "date"), "weekday", Json.getStr(wday, "weekday"),
                    "cond", Json.getStr(wday, "cond"),
                    "temp", Json.getStr(wday, "temp_lo") + "~" + Json.getStr(wday, "temp_hi") + "℃",
                    "items", items, "day_cost", dayCost, "tip", Json.getStr(wday, "tip")));
        }

        int ticketsTotal = 0;
        for (Object d : daysOut) {
            for (Object it : Json.getList((Map<String, Object>) d, "items")) {
                if ("景点".equals(Json.getStr((Map<String, Object>) it, "kind"))) {
                    ticketsTotal += Json.getInt((Map<String, Object>) it, "cost");
                }
            }
        }
        List<String> unused = new ArrayList<>();
        for (Map<String, Object> a : pool) {
            if (!used.contains(Json.getStr(a, "name"))) {
                unused.add(Json.getStr(a, "name"));
            }
        }

        Map<String, Object> hotel = Json.getMap(budget, "hotel");
        return Json.obj(
                "days", daysOut,
                "tickets_total", ticketsTotal,
                "ticket_budget", Json.getInt(budget, "ticket_budget"),
                "unused_attractions", unused,
                "hotel_name", Json.getStr(hotel, "name"));
    }

    private static boolean isRainy(Map<String, Object> day) {
        return Json.getStr(day, "cond").contains("雨") || Json.getInt(day, "rain_prob") >= 60;
    }

    /** 雨天优先室内（安全>就近）；否则优先同片区（就近游览），再按知识库排序。 */
    private static Map<String, Object> pick(List<Map<String, Object>> pool, Set<String> used,
                                            boolean rainy, String preferArea) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> a : pool) {
            if (!used.contains(Json.getStr(a, "name"))) {
                candidates.add(a);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (rainy) {
            List<Map<String, Object>> indoor = new ArrayList<>();
            for (Map<String, Object> a : candidates) {
                if ("室内".equals(Json.getStr(a, "place"))) {
                    indoor.add(a);
                }
            }
            if (!indoor.isEmpty()) {
                candidates = indoor;
            }
        }
        if (preferArea != null) {
            for (Map<String, Object> a : candidates) {
                if (preferArea.equals(Json.getStr(a, "area"))) {
                    return a;
                }
            }
        }
        return candidates.get(0);
    }

    private static Map<String, Object> pickMeal(List<Map<String, Object>> foods, String dayArea, int[] mealI) {
        List<Map<String, Object>> sameArea = new ArrayList<>();
        for (Map<String, Object> f : foods) {
            if (dayArea != null && dayArea.equals(Json.getStr(f, "area"))) {
                sameArea.add(f);
            }
        }
        List<Map<String, Object>> cand = sameArea.isEmpty() ? foods : sameArea;
        Map<String, Object> f = cand.get(mealI[0] % cand.size());
        mealI[0]++;
        return f;
    }

    private static Map<String, Object> item(String time, String type, String kind,
                                            String name, int cost, String note) {
        return new LinkedHashMap<>(Map.of(
                "time", time, "type", type, "kind", kind,
                "name", name, "cost", cost, "note", note));
    }
}
