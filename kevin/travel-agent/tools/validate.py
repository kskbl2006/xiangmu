# -*- coding: utf-8 -*-
"""输出层·结果整合与校验：冲突检查 + 自动修复（对应架构图"冲突检查与方案微调"）。

检查项：
1. 空日程      → 从未使用景点池补位
2. 雨天安排户外 → 与未使用室内景点对调
3. 预算超支    → 酒店降档 → 仍超支则砍低评分付费景点 → 仍超支输出"建议追加预算"
"""
from __future__ import annotations

from tools.base import tool
from tools.poi import ticket_num


def _is_rainy(day: dict) -> bool:
    return "雨" in day["cond"] or day.get("rain_prob", 0) >= 60


@tool("validate")
class ValidateTool(object):
    name = "validate"

    def run(self, params: dict) -> dict:
        req = params["request"]
        poi = params["poi"]
        budget = params["budget"]
        itin = params["itinerary"]
        issues, fixes = [], []

        pool = poi["attractions"]
        used = {it["name"] for d in itin["days"] for it in d["items"] if it["kind"] == "景点"}
        unused = [a for a in pool if a["name"] not in used]
        people = req["people"]

        # 1. 空日程补位
        for d in itin["days"]:
            if not any(it["kind"] == "景点" for it in d["items"]):
                if unused:
                    a = unused.pop(0)
                    used.add(a["name"])
                    d["items"].append({"time": "10:00", "type": "补位", "kind": "景点", "name": a["name"],
                                       "cost": ticket_num(a["ticket"]) * people,
                                       "note": "%s · 评分%.1f" % (a["place"], a["rating"])})
                    d["items"].sort(key=lambda x: x["time"])
                    fixes.append("D%d 日程为空，已补入「%s」" % (d["index"], a["name"]))
                else:
                    issues.append("D%d 无可安排景点（候选池已耗尽）" % d["index"])

        # 2. 雨天户外 → 换室内
        indoor_pool = [a for a in unused if a["place"] == "室内"]
        for d in itin["days"]:
            if not _is_rainy(d):
                continue
            for it in d["items"]:
                if it["kind"] != "景点":
                    continue
                info = next((a for a in pool if a["name"] == it["name"]), None)
                if info and info["place"] == "户外" and indoor_pool:
                    swap = indoor_pool.pop(0)
                    old_name, old_cost = it["name"], it["cost"]
                    it["name"] = swap["name"]
                    it["cost"] = ticket_num(swap["ticket"]) * people
                    it["note"] += "（雨天由「%s」替换）" % old_name
                    fixes.append("D%d 有雨：户外「%s」已替换为室内「%s」" % (d["index"], old_name, swap["name"]))

        def tickets_now():
            return sum(it["cost"] for d in itin["days"] for it in d["items"] if it["kind"] == "景点")

        # 3. 预算控制
        actual = (budget["transport"]["total"] + budget["hotel"]["total"]
                  + budget["food"]["total"] + tickets_now())
        cap = budget["cap"]
        hotel = budget["hotel"]
        if actual > cap and hotel.get("cheapest") and hotel["cheapest"]["price"] < hotel["per_night"]:
            saved = (hotel["per_night"] - hotel["cheapest"]["price"]) * hotel["nights"]
            hotel["name"] = hotel["cheapest"]["name"]
            hotel["tier"] = hotel["cheapest"].get("tier", "")
            hotel["per_night"] = hotel["cheapest"]["price"]
            hotel["total"] = hotel["per_night"] * hotel["nights"]
            itin["hotel_name"] = hotel["name"]
            actual -= saved
            fixes.append("预算超支：住宿降档为「%s」（%d元/晚，省 %d 元）"
                         % (hotel["name"], hotel["per_night"], saved))

        paid = sorted((it for d in itin["days"] for it in d["items"]
                       if it["kind"] == "景点" and it["cost"] > 0), key=lambda x: x["cost"], reverse=True)
        while actual > cap and paid:
            drop = paid.pop()
            saved = drop["cost"]
            for d in itin["days"]:
                if drop in d["items"]:
                    d["items"].remove(drop)
                    d["day_cost"] -= saved
            actual -= saved
            fixes.append("预算超支：移除付费景点「%s」（省 %d 元）" % (drop["name"], saved))

        # 4. 裁剪后空日程补位：优先免费景点，实在没有则记录风险
        scheduled = {it["name"] for d in itin["days"] for it in d["items"] if it["kind"] == "景点"}
        for d in itin["days"]:
            if any(it["kind"] == "景点" for it in d["items"]):
                continue
            free = [a for a in pool if a["name"] not in scheduled and ticket_num(a["ticket"]) == 0]
            if free:
                a = free[0]
                scheduled.add(a["name"])
                d["items"].append({"time": "10:00", "type": "补位", "kind": "景点", "name": a["name"],
                                   "cost": 0, "note": "%s · 评分%.1f（预算受限，安排免费景点）" % (a["place"], a["rating"])})
                d["items"].sort(key=lambda x: x["time"])
                fixes.append("D%d 景点被预算裁剪后空缺，已补入免费景点「%s」" % (d["index"], a["name"]))
            else:
                issues.append("D%d 因预算限制当日无景点安排（免费景点已用尽），建议追加预算" % d["index"])

        # 5. 雨天保留户外的风险提示（无室内景点可换时）
        for d in itin["days"]:
            if not _is_rainy(d):
                continue
            for it in d["items"]:
                if it["kind"] != "景点":
                    continue
                info = next((a for a in pool if a["name"] == it["name"]), None)
                if info and info["place"] == "户外":
                    issues.append("D%d 有雨且无室内景点可换，「%s」保留户外安排，请备好雨具并预留弹性"
                                  % (d["index"], it["name"]))

        if actual > cap:
            issues.append("当前预算 %d 元仍不足以覆盖最低配置（约需 %d 元），建议追加预算或缩减天数/人数" % (cap, actual))

        for d in itin["days"]:
            d["day_cost"] = sum(it["cost"] for it in d["items"])
        itin["tickets_total"] = tickets_now()

        ok = not issues

        # 质量自评（Agent 自评，写入方案文档）：日程覆盖40 + 雨天安全30 + 预算达成30
        total_days = len(itin["days"])

        def day_outdoor(d):
            return [it for it in d["items"] if it["kind"] == "景点"
                    and next((a["place"] for a in pool if a["name"] == it["name"]), "户外") == "户外"]

        covered = sum(1 for d in itin["days"] if any(it["kind"] == "景点" for it in d["items"]))
        rainy_days = [d for d in itin["days"] if _is_rainy(d)]
        safe_rain = sum(1 for d in rainy_days if not day_outdoor(d))
        cov = covered / max(total_days, 1)
        rain_s = (safe_rain / len(rainy_days)) if rainy_days else 1.0
        budget_s = 1.0 if actual <= cap else max(0.0, cap / max(actual, 1))
        score = int(cov * 40 + rain_s * 30 + budget_s * 30)

        return {
            "ok": ok, "issues": issues, "fixes": fixes,
            "actual_total": actual, "cap": cap,
            "quality": {
                "score": score,
                "coverage": {"value": round(cov * 100), "weight": 40,
                             "detail": "%d/%d 天有景点安排" % (covered, total_days)},
                "rain_safety": {"value": round(rain_s * 100), "weight": 30,
                                "detail": ("%d/%d 个雨天无户外暴露" % (safe_rain, len(rainy_days))) if rainy_days else "无雨天"},
                "budget_fit": {"value": round(budget_s * 100), "weight": 30,
                               "detail": "实际 %d 元 / 预算 %d 元" % (actual, cap)},
            },
            "itinerary": itin, "hotel": hotel, "budget": budget,
        }
