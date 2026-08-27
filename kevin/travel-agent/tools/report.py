# -*- coding: utf-8 -*-
"""输出层·文档生成 Skill：把全链路结果渲染为结构化《旅行方案》Markdown。"""
from __future__ import annotations

from datetime import datetime

from config import RUNS_DIR
from tools.base import tool


def _table(headers, rows):
    lines = ["| " + " | ".join(headers) + " |",
             "|" + "|".join(["---"] * len(headers)) + "|"]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(lines)


@tool("report")
class ReportTool(object):
    name = "report"

    def run(self, params: dict) -> dict:
        req = params["request"]
        r = params["results"]
        weather, poi, budget = r["weather"], r["poi"], r["budget"]
        vd = r["validate"]
        itin = vd["itinerary"]
        stats = params["stats"]

        L = []
        L.append("# %s%d日旅行方案" % (req["destination"], req["days"]))
        L.append("")
        L.append("> 由智能旅行规划助手 Agent 生成 · run_id: `%s` · %s · 数据源：天气=%s / 景点=%s"
                 % (stats["run_id"], datetime.now().strftime("%Y-%m-%d %H:%M"),
                    weather.get("source"), poi.get("source")))
        L.append("")

        # 一、需求概览
        L.append("## 一、需求概览")
        L.append("")
        rows = [("目的地", req["destination"]), ("出发地", req["depart_city"]),
                ("出行日期", "%s 起共 %d 天" % (req["start_date"], req["days"])),
                ("人数", "%d大%d小（共%d人）" % (req["adults"], req["children"], req["people"])),
                ("总预算", "%d 元" % req["budget"]),
                ("偏好", "、".join(req["preferences"]) or "未指定")]
        if req["notes"]:
            rows.append(("参数补全", "；".join(req["notes"])))
        L.append(_table(["项目", "内容"], rows))
        L.append("")

        # 二、天气
        L.append("## 二、出行期间天气")
        L.append("")
        L.append(_table(["日期", "星期", "天气", "气温", "降雨概率", "提示"],
                        [(d["date"], d["weekday"], d["cond"], "%d~%d℃" % (d["temp_lo"], d["temp_hi"]),
                          "%d%%" % d["rain_prob"], d["tip"]) for d in weather["days"]]))
        L.append("")

        # 三、每日行程
        L.append("## 三、每日行程（住宿：%s，%d元/晚 × %d晚）"
                 % (vd["hotel"]["name"], vd["hotel"]["per_night"], budget["nights"]))
        L.append("")
        for d in itin["days"]:
            L.append("### D%d %s（%s）%s %s" % (d["index"], d["date"], d["weekday"], d["cond"], d["temp"]))
            L.append("")
            L.append(_table(["时间", "安排", "类型", "费用(全队)", "说明"],
                            [(it["time"], it["name"], it["type"], "%d元" % it["cost"] if it["cost"] else "免费", it["note"])
                             for it in d["items"]]))
            L.append("")
            if d["tip"]:
                L.append("> 当日提示：%s" % d["tip"])
                L.append("")

        # 四、预算
        L.append("## 四、预算明细（%d人%d天）" % (req["people"], req["days"]))
        L.append("")
        rows = [
            ("交通", "%s⇄%s 约%dkm，%d元/人往返" % (req["depart_city"], req["destination"],
                                             budget["transport"]["dist_km"], budget["transport"]["per_person"]),
             budget["transport"]["total"]),
            ("住宿", "%s（%s）%d元/晚×%d晚" % (vd["hotel"]["name"], vd["hotel"]["tier"],
                                          vd["hotel"]["per_night"], budget["nights"]),
             vd["hotel"]["total"]),
            ("餐饮", "约%d元/人/天（2正餐）" % budget["food"]["per_person_day"], budget["food"]["total"]),
            ("门票", "按实际编排核算", itin["tickets_total"]),
        ]
        actual = vd["actual_total"]
        rows.append(("合计", "交通+住宿+餐饮+门票", actual))
        L.append(_table(["项目", "明细", "金额(元)"], rows))
        L.append("")
        if actual <= req["budget"]:
            L.append("**预算结论**：合计 %d 元 ≤ 预算 %d 元，余量 %d 元（建议保留 10%% 机动金）。"
                     % (actual, req["budget"], req["budget"] - actual))
        else:
            L.append("**预算结论**：⚠ 合计 %d 元超出预算 %d 元，建议追加预算或缩减行程。" % (actual, req["budget"]))
        L.append("")

        # 五、校验与修复
        L.append("## 五、Agent 自检与自动修复记录")
        L.append("")
        if vd["fixes"]:
            L.extend(["- ✅ " + f for f in vd["fixes"]])
        if vd["issues"]:
            L.extend(["- ⚠ " + i for i in vd["issues"]])
        if not vd["fixes"] and not vd["issues"]:
            L.append("- ✅ 全部检查通过：日程完整、无雨天户外冲突、预算可控")
        L.append("")

        # 六、注意事项
        L.append("## 六、注意事项")
        L.append("")
        L.extend(["- " + t for t in poi["tips"]])
        L.append("- 出行前请再次核对天气与票务信息；儿童门票、景区预约政策以官方为准。")
        L.append("")

        # 七、运行统计
        sec = stats["task_seconds"]
        L.append("## 七、运行统计（Agent 过程可观测）")
        L.append("")
        L.append(_table(["子任务", "耗时(s)"],
                        [(name, "%.2f" % sec[name]) for name in sorted(sec)] or [("-", "-")]))
        L.append("")
        L.append("执行记录：%s；%s" % (" → ".join(stats["executed"] + ["report"]),
                                    stats["token_report"].replace("\n", " ")))
        L.append("")

        fname = "旅行方案_%s%d日_%s.md" % (req["destination"], req["days"], stats["run_id"])
        path = RUNS_DIR / stats["run_id"] / fname
        path.write_text("\n".join(L), encoding="utf-8")
        return {"path": str(path), "filename": fname}
