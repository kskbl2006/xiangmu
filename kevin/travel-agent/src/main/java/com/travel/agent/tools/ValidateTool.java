package com.travel.agent.tools;

import com.travel.agent.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 输出层·结果整合与校验：冲突检查 + 自动修复（对应架构图"冲突检查与方案微调"）。
 *
 * 检查项：
 * 1. 空日程      → 从未使用景点池补位
 * 2. 雨天安排户外 → 与未使用室内景点对调
 * 3. 预算超支    → 酒店降档 → 仍超支则砍低评分付费景点 → 仍超支输出"建议追加预算"
 * 4. 裁剪后空日程补位：优先免费景点，实在没有则记录风险
 * 5. 雨天保留户外的风险提示（无室内景点可换时）
 *
 * 质量自评：日程覆盖40 + 雨天安全30 + 预算达成30。
 */
public final class ValidateTool implements Tool {

    @Override
    public String name() {
        return "validate";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> params) {
        Map<String, Object> req = Json.getMap(params, "request");
        Map<String, Object> poi = Json.getMap(params, "poi");
        Map<String, Object> budget = Json.getMap(params, "budget");
        Map<String, Object> itin = Json.getMap(params, "itinerary");
        List<String> issues = new ArrayList<>();
        List<String> fixes = new ArrayList<>();

        List<Map<String, Object>> pool = Json.getMaps(poi, "attractions");
        Set<String> used = new HashSet<>();
        List<Object> days = Json.getList(itin, "days");
        for (Object d : days) {
            for (Object it : Json.getList((Map<String, Object>) d, "items")) {
                if ("景点".equals(Json.getStr((Map<String, Object>) it, "kind"))) {
                    used.add(Json.getStr((Map<String, Object>) it, "name"));
                }
            }
        }
        List<Map<String, Object>> unused = new ArrayList<>();
        for (Map<String, Object> a : pool) {
            if (!used.contains(Json.getStr(a, "name"))) {
                unused.add(a);
            }
        }
        int people = Json.getInt(req, "people");

        // 1. 空日程补位
        for (Object dObj : days) {
            Map<String, Object> d = (Map<String, Object>) dObj;
            boolean hasAttraction = false;
            for (Object it : Json.getList(d, "items")) {
                if ("景点".equals(Json.getStr((Map<String, Object>) it, "kind"))) {
                    hasAttraction = true;
                    break;
                }
            }
            if (hasAttraction) {
                continue;
            }
            if (!unused.isEmpty()) {
                Map<String, Object> a = unused.remove(0);
                used.add(Json.getStr(a, "name"));
                Json.getList(d, "items").add(Json.obj(
                        "time", "10:00", "type", "补位", "kind", "景点",
                        "name", Json.getStr(a, "name"),
                        "cost", PoiTool.ticketNum(Json.getStr(a, "ticket")) * people,
                        "note", String.format("%s · 评分%.1f", Json.getStr(a, "place"), Json.getDbl(a, "rating"))));
                sortByTime(d);
                fixes.add(String.format("D%d 日程为空，已补入「%s」", Json.getInt(d, "index"), Json.getStr(a, "name")));
            } else {
                issues.add(String.format("D%d 无可安排景点（候选池已耗尽）", Json.getInt(d, "index")));
            }
        }

        // 2. 雨天户外 → 换室内
        List<Map<String, Object>> indoorPool = new ArrayList<>();
        for (Map<String, Object> a : unused) {
            if ("室内".equals(Json.getStr(a, "place"))) {
                indoorPool.add(a);
            }
        }
        for (Object dObj : days) {
            Map<String, Object> d = (Map<String, Object>) dObj;
            if (!isRainy(d)) {
                continue;
            }
            for (Object itObj : Json.getList(d, "items")) {
                Map<String, Object> it = (Map<String, Object>) itObj;
                if (!"景点".equals(Json.getStr(it, "kind"))) {
                    continue;
                }
                Map<String, Object> info = findByName(pool, Json.getStr(it, "name"));
                if (info != null && "户外".equals(Json.getStr(info, "place")) && !indoorPool.isEmpty()) {
                    Map<String, Object> swap = indoorPool.remove(0);
                    String oldName = Json.getStr(it, "name");
                    it.put("name", Json.getStr(swap, "name"));
                    it.put("cost", PoiTool.ticketNum(Json.getStr(swap, "ticket")) * people);
                    it.put("note", Json.getStr(it, "note") + "（雨天由「" + oldName + "」替换）");
                    fixes.add(String.format("D%d 有雨：户外「%s」已替换为室内「%s」",
                            Json.getInt(d, "index"), oldName, Json.getStr(swap, "name")));
                }
            }
        }

        // 3. 预算控制
        int actual = Json.intVal(Json.getMap(budget, "transport").get("total"))
                + Json.intVal(Json.getMap(budget, "hotel").get("total"))
                + Json.intVal(Json.getMap(budget, "food").get("total"))
                + ticketsNow(days);
        int cap = Json.getInt(budget, "cap");
        Map<String, Object> hotel = Json.getMap(budget, "hotel");
        Map<String, Object> cheapest = Json.getMap(hotel, "cheapest");
        if (actual > cap && cheapest != null && Json.getInt(cheapest, "price") < Json.getInt(hotel, "per_night")) {
            int saved = (Json.getInt(hotel, "per_night") - Json.getInt(cheapest, "price")) * Json.getInt(hotel, "nights");
            hotel.put("name", Json.getStr(cheapest, "name"));
            hotel.put("tier", Json.getStr(cheapest, "tier"));
            hotel.put("per_night", Json.getInt(cheapest, "price"));
            hotel.put("total", Json.getInt(cheapest, "price") * Json.getInt(hotel, "nights"));
            itin.put("hotel_name", Json.getStr(hotel, "name"));
            actual -= saved;
            fixes.add(String.format("预算超支：住宿降档为「%s」（%d元/晚，省 %d 元）",
                    Json.getStr(hotel, "name"), Json.getInt(hotel, "per_night"), saved));
        }

        List<Map<String, Object>> paid = new ArrayList<>();
        for (Object dObj : days) {
            for (Object itObj : Json.getList((Map<String, Object>) dObj, "items")) {
                Map<String, Object> it = (Map<String, Object>) itObj;
                if ("景点".equals(Json.getStr(it, "kind")) && Json.getInt(it, "cost") > 0) {
                    paid.add(it);
                }
            }
        }
        paid.sort((a, b) -> Integer.compare(Json.getInt(b, "cost"), Json.getInt(a, "cost"))); // 费用降序：从最便宜的开始砍
        while (actual > cap && !paid.isEmpty()) {
            Map<String, Object> drop = paid.remove(paid.size() - 1);   // 移除当前最便宜的付费景点
            int saved = Json.getInt(drop, "cost");
            for (Object dObj : days) {
                Json.getList((Map<String, Object>) dObj, "items").removeIf(x -> x == drop);
            }
            actual -= saved;
            fixes.add(String.format("预算超支：移除付费景点「%s」（省 %d 元）", Json.getStr(drop, "name"), saved));
        }

        // 4. 裁剪后空日程补位：优先免费景点，实在没有则记录风险
        Set<String> scheduled = new HashSet<>();
        for (Object dObj : days) {
            for (Object it : Json.getList((Map<String, Object>) dObj, "items")) {
                if ("景点".equals(Json.getStr((Map<String, Object>) it, "kind"))) {
                    scheduled.add(Json.getStr((Map<String, Object>) it, "name"));
                }
            }
        }
        for (Object dObj : days) {
            Map<String, Object> d = (Map<String, Object>) dObj;
            boolean hasAttraction = false;
            for (Object it : Json.getList(d, "items")) {
                if ("景点".equals(Json.getStr((Map<String, Object>) it, "kind"))) {
                    hasAttraction = true;
                    break;
                }
            }
            if (hasAttraction) {
                continue;
            }
            Map<String, Object> free = null;
            for (Map<String, Object> a : pool) {
                if (!scheduled.contains(Json.getStr(a, "name")) && PoiTool.ticketNum(Json.getStr(a, "ticket")) == 0) {
                    free = a;
                    break;
                }
            }
            if (free != null) {
                scheduled.add(Json.getStr(free, "name"));
                Json.getList(d, "items").add(Json.obj(
                        "time", "10:00", "type", "补位", "kind", "景点",
                        "name", Json.getStr(free, "name"), "cost", 0,
                        "note", String.format("%s · 评分%.1f（预算受限，安排免费景点）",
                                Json.getStr(free, "place"), Json.getDbl(free, "rating"))));
                sortByTime(d);
                fixes.add(String.format("D%d 景点被预算裁剪后空缺，已补入免费景点「%s」",
                        Json.getInt(d, "index"), Json.getStr(free, "name")));
            } else {
                issues.add(String.format("D%d 因预算限制当日无景点安排（免费景点已用尽），建议追加预算", Json.getInt(d, "index")));
            }
        }

        // 5. 雨天保留户外的风险提示
        for (Object dObj : days) {
            Map<String, Object> d = (Map<String, Object>) dObj;
            if (!isRainy(d)) {
                continue;
            }
            for (Object itObj : Json.getList(d, "items")) {
                Map<String, Object> it = (Map<String, Object>) itObj;
                if (!"景点".equals(Json.getStr(it, "kind"))) {
                    continue;
                }
                Map<String, Object> info = findByName(pool, Json.getStr(it, "name"));
                if (info != null && "户外".equals(Json.getStr(info, "place"))) {
                    issues.add(String.format("D%d 有雨且无室内景点可换，「%s」保留户外安排，请备好雨具并预留弹性",
                            Json.getInt(d, "index"), Json.getStr(it, "name")));
                }
            }
        }

        if (actual > cap) {
            issues.add(String.format("当前预算 %d 元仍不足以覆盖最低配置（约需 %d 元），建议追加预算或缩减天数/人数", cap, actual));
        }

        for (Object dObj : days) {
            Map<String, Object> d = (Map<String, Object>) dObj;
            int dayCost = 0;
            for (Object it : Json.getList(d, "items")) {
                dayCost += Json.getInt((Map<String, Object>) it, "cost");
            }
            d.put("day_cost", dayCost);
        }
        itin.put("tickets_total", ticketsNow(days));

        // ---- 质量自评 ----
        int totalDays = days.size();
        int covered = 0;
        for (Object d : days) {
            for (Object it : Json.getList((Map<String, Object>) d, "items")) {
                if ("景点".equals(Json.getStr((Map<String, Object>) it, "kind"))) {
                    covered++;
                    break;
                }
            }
        }
        List<Map<String, Object>> rainyDays = new ArrayList<>();
        for (Object d : days) {
            if (isRainy((Map<String, Object>) d)) {
                rainyDays.add((Map<String, Object>) d);
            }
        }
        int safeRain = 0;
        for (Map<String, Object> d : rainyDays) {
            boolean hasOutdoor = false;
            for (Object itObj : Json.getList(d, "items")) {
                Map<String, Object> it = (Map<String, Object>) itObj;
                if (!"景点".equals(Json.getStr(it, "kind"))) {
                    continue;
                }
                Map<String, Object> info = findByName(pool, Json.getStr(it, "name"));
                String place = info == null ? "户外" : Json.getStr(info, "place");
                if ("户外".equals(place)) {
                    hasOutdoor = true;
                    break;
                }
            }
            if (!hasOutdoor) {
                safeRain++;
            }
        }
        double cov = (double) covered / Math.max(totalDays, 1);
        double rainS = rainyDays.isEmpty() ? 1.0 : (double) safeRain / rainyDays.size();
        double budgetS = actual <= cap ? 1.0 : Math.max(0.0, (double) cap / Math.max(actual, 1));
        int score = (int) (cov * 40 + rainS * 30 + budgetS * 30);

        return Json.obj(
                "ok", issues.isEmpty(), "issues", issues, "fixes", fixes,
                "actual_total", actual, "cap", cap,
                "quality", Json.obj(
                        "score", score,
                        "coverage", Json.obj("value", (int) Math.round(cov * 100), "weight", 40,
                                "detail", String.format("%d/%d 天有景点安排", covered, totalDays)),
                        "rain_safety", Json.obj("value", (int) Math.round(rainS * 100), "weight", 30,
                                "detail", rainyDays.isEmpty() ? "无雨天"
                                        : String.format("%d/%d 个雨天无户外暴露", safeRain, rainyDays.size())),
                        "budget_fit", Json.obj("value", (int) Math.round(budgetS * 100), "weight", 30,
                                "detail", String.format("实际 %d 元 / 预算 %d 元", actual, cap))),
                "itinerary", itin, "hotel", hotel, "budget", budget);
    }

    private static boolean isRainy(Map<String, Object> day) {
        return Json.getStr(day, "cond").contains("雨") || Json.getInt(day, "rain_prob") >= 60;
    }

    private static int ticketsNow(List<Object> days) {
        int sum = 0;
        for (Object d : days) {
            for (Object it : Json.getList((Map<String, Object>) d, "items")) {
                if ("景点".equals(Json.getStr((Map<String, Object>) it, "kind"))) {
                    sum += Json.getInt((Map<String, Object>) it, "cost");
                }
            }
        }
        return sum;
    }

    private static Map<String, Object> findByName(List<Map<String, Object>> pool, String name) {
        for (Map<String, Object> a : pool) {
            if (name.equals(Json.getStr(a, "name"))) {
                return a;
            }
        }
        return null;
    }

    private static void sortByTime(Map<String, Object> d) {
        Json.getList(d, "items").sort(Comparator.comparing(
                o -> Json.getStr((Map<String, Object>) o, "time")));
    }
}
