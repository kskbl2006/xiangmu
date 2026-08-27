# -*- coding: utf-8 -*-
"""景点/美食/酒店检索工具：本地旅行知识库 + RAG 相关性排序。

知识库格式（knowledge/<城市>.md）：
  ## 景点
  - 名称 | 标签 | 户外|室内 | 评分 | 门票 | 建议时长 | 区域 | 说明
  ## 美食
  - 名称 | 类型 | 评分 | 人均XX元 | 区域 | 说明
  ## 酒店
  - 名称 | 档次 | 评分 | XXX元/晚 | 区域 | 说明
  ## 提示
  - 文本

未覆盖城市自动降级为"通用推荐"数据，保证闭环不中断（Mock 兜底设计）。
"""
from __future__ import annotations

import re

from config import KNOWLEDGE_DIR, POI_CACHE_TTL
from rag.kb import relevance
from tools.base import cache_get, cache_set, tool

GENERIC_ATTRACTIONS = [
    ("城市中心广场", "地标", "户外", 4.3, "免费", "2小时", "市中心", "地标打卡，感受城市风貌"),
    ("市博物馆", "文化", "室内", 4.5, "免费", "3小时", "市中心", "了解城市历史文化的最佳去处"),
    ("滨水步行街", "街区", "户外", 4.4, "免费", "3小时", "沿江/沿河", "夜景与美食聚集的休闲街区"),
    ("当地美食街", "美食", "户外", 4.3, "免费", "2小时", "老城区", "汇集地方特色小吃"),
    ("科技馆", "亲子", "室内", 4.4, "60元", "3小时", "新区", "互动展项丰富，适合亲子"),
    ("城市公园", "自然", "户外", 4.2, "免费", "2小时", "市区", "本地人休闲首选"),
]
GENERIC_FOODS = [
    ("本地特色餐厅", "地方菜", 4.4, 80, "市中心", "口碑稳定的本地菜"),
    ("老字号小吃店", "小吃", 4.3, 40, "老城区", "传统小吃"),
    ("连锁简餐", "简餐", 4.2, 50, "商圈", "快捷卫生"),
    ("夜市大排档", "夜市", 4.3, 70, "夜市街", "夜间觅食好去处"),
]
GENERIC_HOTELS = [
    ("经济连锁酒店", "经济", 4.2, 180, "交通便利处", "性价比之选"),
    ("商务舒适酒店", "舒适", 4.4, 380, "市中心", "位置与品质均衡"),
    ("高端度假酒店", "豪华", 4.6, 880, "景区附近", "设施齐全"),
]


CITY_FILES = {"三亚": "sanya", "上海": "shanghai", "北京": "beijing",
              "成都": "chengdu", "杭州": "hangzhou"}


def load_kb(destination: str):
    candidates = [KNOWLEDGE_DIR / ("%s.md" % destination)]
    if destination in CITY_FILES:
        candidates.append(KNOWLEDGE_DIR / ("%s.md" % CITY_FILES[destination]))
    for path in candidates:
        if path.exists():
            return _parse_kb(path)
    # 兜底：按文件头 "# 城市：X" 匹配（新增知识库文件无需改代码）
    for path in KNOWLEDGE_DIR.glob("*.md"):
        first = path.read_text(encoding="utf-8").splitlines()[:1]
        if first and destination in first[0]:
            return _parse_kb(path)
    return None


def _parse_kb(path):
    kb = {"attractions": [], "foods": [], "hotels": [], "tips": []}
    section = None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("## "):
            name = line[3:].strip()
            section = {"景点": "attractions", "美食": "foods", "酒店": "hotels", "提示": "tips"}.get(name)
            continue
        if not line.startswith("- ") or section is None:
            continue
        parts = [p.strip() for p in line[2:].split("|")]
        if section == "attractions" and len(parts) >= 8:
            kb["attractions"].append({
                "name": parts[0], "tags": parts[1], "place": parts[2],
                "rating": float(parts[3]), "ticket": parts[4], "duration": parts[5],
                "area": parts[6], "desc": parts[7],
            })
        elif section == "foods" and len(parts) >= 6:
            kb["foods"].append({
                "name": parts[0], "type": parts[1], "rating": float(parts[2]),
                "avg_cost": _price(parts[3]), "area": parts[4], "desc": parts[5],
            })
        elif section == "hotels" and len(parts) >= 6:
            kb["hotels"].append({
                "name": parts[0], "tier": parts[1], "rating": float(parts[2]),
                "price": _price(parts[3]), "area": parts[4], "desc": parts[5],
            })
        elif section == "tips":
            kb["tips"].append(line[2:].strip())
    return kb if kb["attractions"] else None


def _price(text: str) -> int:
    m = re.search(r"(\d+)", str(text))
    return int(m.group(1)) if m else 0


def ticket_num(ticket: str) -> int:
    return _price(ticket)


@tool("poi")
class PoiTool(object):
    name = "poi"

    def run(self, params: dict) -> dict:
        dest = params["destination"]
        days = params["days"]
        prefs = params.get("preferences") or []
        key = "poi:%s:%s:%d" % (dest, ",".join(prefs), days)
        cached = cache_get(key, POI_CACHE_TTL)
        if cached:
            cached["source"] = "cache"
            return cached

        kb = load_kb(dest)
        source = "kb"
        if kb is None:
            source = "generic"
            kb = {
                "attractions": [dict(zip(
                    ("name", "tags", "place", "rating", "ticket", "duration", "area", "desc"), a))
                    for a in GENERIC_ATTRACTIONS],
                "foods": [dict(zip(("name", "type", "rating", "avg_cost", "area", "desc"), f))
                          for f in GENERIC_FOODS],
                "hotels": [dict(zip(("name", "tier", "rating", "price", "area", "desc"), h))
                           for h in GENERIC_HOTELS],
                "tips": ["该城市暂无专属知识库，已使用通用推荐数据（可扩充 knowledge/ 目录）"],
            }

        # RAG 排序：需求相关度 + 评分 + 偏好标签命中
        query = dest + " " + " ".join(prefs) + " " + params.get("goal", "")[:30]
        def rank_att(a):
            score = relevance(query, a["name"] + a["tags"] + a["desc"]) * 10 + a["rating"] * 2
            if any(p in a["tags"] for p in prefs):
                score += 4
            return score

        attractions = sorted(kb["attractions"], key=rank_att, reverse=True)
        foods = sorted(kb["foods"], key=lambda f: relevance(query, f["name"] + f["type"]) * 10 + f["rating"] * 2, reverse=True)
        hotels = sorted(kb["hotels"], key=lambda h: h["rating"], reverse=True)

        data = {
            "destination": dest,
            "source": source,
            # token 压缩：只保留行程编排所需字段，截断长描述
            "attractions": [{"name": a["name"], "tags": a["tags"], "place": a["place"],
                             "rating": a["rating"], "ticket": a["ticket"],
                             "duration": a["duration"], "area": a["area"],
                             "desc": a["desc"][:30]}
                            for a in attractions[:max(days * 2 + 2, 8)]],
            "foods": [{"name": f["name"], "type": f["type"], "rating": f["rating"],
                       "avg_cost": f["avg_cost"], "area": f["area"]}
                      for f in foods[:max(days * 2, 4)]],
            "hotels": hotels[:3],
            "tips": kb["tips"],
        }
        cache_set(key, data)
        return data
