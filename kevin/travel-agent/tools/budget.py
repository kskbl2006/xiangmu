# -*- coding: utf-8 -*-
"""预算计算工具：交通（按城市间距离估算）+ 住宿 + 餐饮 + 门票 + 购物预留。"""
from __future__ import annotations

from math import asin, cos, radians, sin, sqrt

from tools.base import tool
from tools.weather import CITY_COORDS


def haversine_km(a, b) -> float:
    if a not in CITY_COORDS or b not in CITY_COORDS:
        return 800.0  # 未覆盖城市给一个中长途默认距离
    la1, lo1 = map(radians, CITY_COORDS[a])
    la2, lo2 = map(radians, CITY_COORDS[b])
    h = sin((la2 - la1) / 2) ** 2 + cos(la1) * cos(la2) * sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371 * asin(sqrt(h))


@tool("budget")
class BudgetTool(object):
    name = "budget"

    def run(self, params: dict) -> dict:
        req = params["request"]
        hotels = params["hotels"]
        foods = params["foods"]

        people, days = req["people"], req["days"]
        nights = max(days - 1, 1)

        # 交通：距离越远越接近机票价，简单分段估算（往返/人）
        dist = haversine_km(req["depart_city"], req["destination"])
        if dist < 30:
            transport_pp = 50            # 同城
        else:
            transport_pp = min(1600, int(120 + dist * 0.6))
        transport_total = transport_pp * people

        # 住宿：默认取中档（评分排序第 2 的酒店），校验层可自动降档
        by_price = sorted(hotels, key=lambda h: h["price"])
        mid = by_price[len(by_price) // 2] if by_price else {"name": "默认酒店", "price": 300, "tier": "舒适"}
        hotel_total = mid["price"] * nights

        # 餐饮：按美食人均 × 2 正餐/天
        food_pp_day = int(sum(f["avg_cost"] for f in foods) / max(len(foods), 1) * 2)
        food_total = food_pp_day * days * people

        # 门票：按候选景点均值估一个预留额，校验层再用实际编排结果修正
        atts = params.get("attractions") or []
        avg_ticket = sum(int("".join(c for c in a["ticket"] if c.isdigit()) or 0) for a in atts) / max(len(atts), 1)
        ticket_budget = int(avg_ticket * 1.5 * days * people)

        shopping = int(req["budget"] * 0.1)
        planned_total = transport_total + hotel_total + food_total + ticket_budget + shopping

        warnings = []
        if planned_total > req["budget"]:
            warnings.append("按默认配置预估总花费 %d 元，超过预算 %d 元，校验层将自动降档调整"
                            % (planned_total, req["budget"]))

        return {
            "people": people, "days": days, "nights": nights,
            "transport": {"per_person": transport_pp, "total": transport_total, "dist_km": int(dist)},
            "hotel": {"name": mid["name"], "tier": mid.get("tier", ""), "per_night": mid["price"],
                      "nights": nights, "total": hotel_total, "cheapest": by_price[0] if by_price else mid},
            "food": {"per_person_day": food_pp_day, "total": food_total},
            "ticket_budget": ticket_budget,
            "shopping": shopping,
            "planned_total": planned_total,
            "cap": req["budget"],
            "warnings": warnings,
        }
