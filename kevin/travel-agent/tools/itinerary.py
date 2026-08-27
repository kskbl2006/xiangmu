# -*- coding: utf-8 -*-
"""行程编排工具：逐日时间槽 + 天气感知（雨天优先室内）+ 餐饮轮换。"""
from __future__ import annotations

import re

from config import MAX_POI_IN_ITINERARY_STEP
from tools.base import tool
from tools.poi import ticket_num


def _is_rainy(day: dict) -> bool:
    return "雨" in day["cond"] or day.get("rain_prob", 0) >= 60


@tool("itinerary")
class ItineraryTool(object):
    name = "itinerary"

    def run(self, params: dict) -> dict:
        req = params["request"]
        weather = params["weather"]
        poi = params["poi"]
        budget = params["budget"]

        people = req["people"]
        # token 压缩：只取排序后的前 N 个候选进入编排
        pool = poi["attractions"][:MAX_POI_IN_ITINERARY_STEP]
        used = set()
        foods = poi["foods"]
        days_out = []
        meal_i = [0]  # 全局轮换指针（同区域优先时避免连吃同一家）

        def pick_meal(day_area):
            same_area = [f for f in foods if f["area"] == day_area]
            cand = same_area or foods
            f = cand[meal_i[0] % len(cand)]
            meal_i[0] += 1
            return f

        for i, wday in enumerate(weather["days"]):
            rainy = _is_rainy(wday)
            items = []
            day_area = None

            # 上/下午各安排 1 个景点；不雨天且有余量时加夜间活动
            # 同区域就近：当日首个景点确定片区，后续优先安排同片区景点与餐厅
            slots = [("09:00", "上午"), ("14:00", "下午")]
            if not rainy:
                slots.append(("20:00", "夜游"))
            for time, label in slots:
                pick = self._pick(pool, used, rainy, prefer_area=day_area)
                if not pick:
                    break
                used.add(pick["name"])
                if day_area is None:
                    day_area = pick["area"]
                cost = ticket_num(pick["ticket"]) * people
                note = "%s · %s · 评分%.1f" % (pick["duration"], pick["place"], pick["rating"])
                if rainy and pick["place"] == "室内":
                    note += "（雨天安排）"
                items.append({"time": time, "type": label, "kind": "景点",
                              "name": pick["name"], "cost": cost, "note": note})

            # 午晚餐：优先当日同片区餐厅，全局轮换防重复
            if foods:
                lunch = pick_meal(day_area)
                dinner = pick_meal(day_area)
                items.append({"time": "12:00", "type": "午餐", "kind": "美食",
                              "name": lunch["name"], "cost": lunch["avg_cost"] * people,
                              "note": "人均%d元 · %s" % (lunch["avg_cost"], lunch["area"])})
                items.append({"time": "18:00", "type": "晚餐", "kind": "美食",
                              "name": dinner["name"], "cost": dinner["avg_cost"] * people,
                              "note": "人均%d元 · %s" % (dinner["avg_cost"], dinner["area"])})

            items.sort(key=lambda x: x["time"])
            day_cost = sum(it["cost"] for it in items)
            days_out.append({
                "index": i + 1,
                "date": wday["date"], "weekday": wday["weekday"], "cond": wday["cond"],
                "temp": "%d~%d℃" % (wday["temp_lo"], wday["temp_hi"]),
                "items": items, "day_cost": day_cost,
                "tip": wday["tip"],
            })

        tickets_total = sum(it["cost"] for d in days_out for it in d["items"] if it["kind"] == "景点")
        unused = [a["name"] for a in pool if a["name"] not in used]

        return {
            "days": days_out,
            "tickets_total": tickets_total,
            "ticket_budget": budget["ticket_budget"],
            "unused_attractions": unused,
            "hotel_name": budget["hotel"]["name"],
        }

    def _pick(self, pool, used, rainy, prefer_area=None):
        """雨天优先室内（安全>就近）；否则优先同片区（就近游览），再按知识库排序。"""
        candidates = [a for a in pool if a["name"] not in used]
        if not candidates:
            return None
        if rainy:
            indoor = [a for a in candidates if a["place"] == "室内"]
            if indoor:
                candidates = indoor
        if prefer_area:
            same = [a for a in candidates if a["area"] == prefer_area]
            if same:
                return same[0]
        return candidates[0]
