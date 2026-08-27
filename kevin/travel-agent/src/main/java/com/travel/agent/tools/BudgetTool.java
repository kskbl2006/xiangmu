package com.travel.agent.tools;

import com.travel.agent.util.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 预算计算工具：交通（按城市间距离估算）+ 住宿 + 餐饮 + 门票 + 购物预留。 */
public final class BudgetTool implements Tool {

    @Override
    public String name() {
        return "budget";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> params) {
        Map<String, Object> req = Json.getMap(params, "request");
        List<Map<String, Object>> hotels = Json.getMaps(params, "hotels");
        List<Map<String, Object>> foods = Json.getMaps(params, "foods");

        int people = Json.getInt(req, "people");
        int days = Json.getInt(req, "days");
        int nights = Math.max(days - 1, 1);

        // 交通：距离越远越接近机票价，简单分段估算（往返/人）
        double dist = haversineKm(Json.getStr(req, "depart_city"), Json.getStr(req, "destination"));
        int transportPp = dist < 30 ? 50 : (int) Math.min(1600, 120 + dist * 0.6);
        int transportTotal = transportPp * people;

        // 住宿：默认取中档（评分排序第 2 的酒店），校验层可自动降档
        List<Map<String, Object>> byPrice = new ArrayList<>(hotels);
        byPrice.sort(Comparator.comparingInt(h -> Json.getInt(h, "price")));
        Map<String, Object> mid;
        Map<String, Object> cheapest;
        if (byPrice.isEmpty()) {
            mid = Json.obj("name", "默认酒店", "price", 300, "tier", "舒适");
            cheapest = mid;
        } else {
            mid = byPrice.get(byPrice.size() / 2);
            cheapest = byPrice.get(0);
        }
        int hotelPerNight = Json.getInt(mid, "price");
        int hotelTotal = hotelPerNight * nights;

        // 餐饮：按美食人均 × 2 正餐/天
        int foodSum = 0;
        for (Map<String, Object> f : foods) {
            foodSum += Json.getInt(f, "avg_cost");
        }
        int foodPpDay = (int) ((double) foodSum / Math.max(foods.size(), 1) * 2);
        int foodTotal = foodPpDay * days * people;

        // 门票：按候选景点均值估一个预留额，校验层再用实际编排结果修正
        List<Map<String, Object>> attractions = Json.getMaps(params, "attractions");
        double avgTicket = 0;
        for (Map<String, Object> a : attractions) {
            avgTicket += PoiTool.ticketNum(Json.getStr(a, "ticket"));
        }
        avgTicket /= Math.max(attractions.size(), 1);
        int ticketBudget = (int) (avgTicket * 1.5 * days * people);

        int budget = Json.getInt(req, "budget");
        int shopping = (int) (budget * 0.1);
        int plannedTotal = transportTotal + hotelTotal + foodTotal + ticketBudget + shopping;

        List<String> warnings = new ArrayList<>();
        if (plannedTotal > budget) {
            warnings.add(String.format("按默认配置预估总花费 %d 元，超过预算 %d 元，校验层将自动降档调整",
                    plannedTotal, budget));
        }

        Map<String, Object> hotelOut = Json.obj(
                "name", Json.getStr(mid, "name"),
                "tier", Json.getStr(mid, "tier"),
                "per_night", hotelPerNight,
                "nights", nights,
                "total", hotelTotal,
                "cheapest", cheapest);

        return Json.obj(
                "people", people, "days", days, "nights", nights,
                "transport", Json.obj("per_person", transportPp, "total", transportTotal, "dist_km", (int) dist),
                "hotel", hotelOut,
                "food", Json.obj("per_person_day", foodPpDay, "total", foodTotal),
                "ticket_budget", ticketBudget,
                "shopping", shopping,
                "planned_total", plannedTotal,
                "cap", budget,
                "warnings", warnings);
    }

    /** 半正矢距离（公里）。未覆盖城市给一个中长途默认距离。 */
    public static double haversineKm(String a, String b) {
        double[] c1 = WeatherTool.CITY_COORDS.get(a);
        double[] c2 = WeatherTool.CITY_COORDS.get(b);
        if (c1 == null || c2 == null) {
            return 800.0;
        }
        double la1 = Math.toRadians(c1[0]);
        double lo1 = Math.toRadians(c1[1]);
        double la2 = Math.toRadians(c2[0]);
        double lo2 = Math.toRadians(c2[1]);
        double h = Math.pow(Math.sin((la2 - la1) / 2), 2)
                + Math.cos(la1) * Math.cos(la2) * Math.pow(Math.sin((lo2 - lo1) / 2), 2);
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }
}
