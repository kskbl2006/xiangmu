# -*- coding: utf-8 -*-
"""需求解析层（NLU）：意图识别 + 实体抽取 + 参数补全 → 结构化需求。

设计取舍（省 token）：正则规则优先，LLM 兜底——
常规旅行需求 0 次 LLM 调用即可完成解析；仅当规则无法判定意图时才询问一次 LLM。
"""
from __future__ import annotations

import re
from datetime import date, timedelta

from core.llm import get_llm

CN_NUM = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
          "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}

# 知识库已覆盖城市（poi 工具对未覆盖城市自动降级为通用推荐）
KNOWN_CITIES = ("三亚", "上海", "北京", "成都", "杭州", "西安", "重庆", "广州", "南京", "苏州")

TRAVEL_KEYWORDS = ("旅行", "旅游", "游", "行程", "攻略", "出发", "度假", "自由行", "跟团", "玩")

PREF_KEYWORDS = {
    "美食": ("美食", "吃", "小吃", "海鲜"),
    "亲子": ("亲子", "孩子", "儿童", "一家", "熊猫"),
    "文化": ("博物馆", "历史", "文化", "古迹", "寺庙"),
    "自然": ("自然", "风景", "山水", "海滩", "海边", "爬山", "公园"),
    "购物": ("购物", "逛街", "商场", "免税"),
    "夜生活": ("夜市", "夜景", "酒吧", "夜生活", "演出"),
}


def is_travel_intent(text: str) -> bool:
    """意图识别：关键词规则优先，命中失败才调用一次 LLM 兜底。"""
    if any(kw in text for kw in TRAVEL_KEYWORDS):
        return True
    try:
        return "是" == get_llm().complete(
            "判断用户输入是否为旅行规划意图，回答是或否。用户输入：%s" % text)
    except Exception:
        return False


def _num(cn: str) -> int:
    return CN_NUM.get(cn, 0)


def _extract_destination(text: str):
    for pat in (r"去([\u4e00-\u9fff]{2,4}?)(?=\d+\s*天|\d*日|玩|旅|游|行|度|，|\s|$)",
                r"([\u4e00-\u9fff]{2,3})(?:\d+|一|二|两|三|四|五|六|七|八|九|十)?日游"):
        m = re.search(pat, text)
        if m:
            for city in KNOWN_CITIES:
                if city in m.group(1):
                    return city
            # 未覆盖城市也返回，poi 工具会降级为通用推荐
            cand = m.group(1).strip()
            if len(cand) >= 2:
                return cand
    return None


def _extract_days(text: str):
    m = re.search(r"(\d+)\s*天", text)
    if m:
        return int(m.group(1))
    m = re.search(r"(\d+)\s*日", text)
    if m:
        return int(m.group(1))
    m = re.search(r"([一二两三四五六七八九十])日游", text)
    if m:
        return _num(m.group(1)) or 3
    m = re.search(r"玩([一二两三四五六七八九十\d]+)天", text)
    if m:
        g = m.group(1)
        return _num(g) if not g.isdigit() else int(g)
    return None


def _extract_budget(text: str):
    m = re.search(r"预算\s*(\d+(?:\.\d+)?)\s*(万|元|块)?", text)
    if m:
        val = float(m.group(1))
        if m.group(2) == "万":
            val *= 10000
        return int(val)
    m = re.search(r"(\d{4,6})\s*(元|块|RMB)", text)
    if m:
        return int(m.group(1))
    return None


def _extract_people(text: str):
    m = re.search(r"(\d+)\s*大\s*(\d+)\s*小", text)
    if m:
        return int(m.group(1)), int(m.group(2))
    m = re.search(r"(\d+)\s*(?:个)?人", text)
    if m:
        return int(m.group(1)), 0
    m = re.search(r"([一二两三四五六七八九十])\s*(?:个)?人", text)
    if m:
        return (_num(m.group(1)) or 2), 0
    return None, None


def _extract_depart(text: str):
    m = re.search(r"从([\u4e00-\u9fff]{2,4}?)(?=出发)", text)
    if m:
        return m.group(1).strip()
    m = re.search(r"([\u4e00-\u9fff]{2,3})出发", text)
    if m:
        return m.group(1).strip()
    return None


def _extract_start_date(text: str):
    today = date.today()
    m = re.search(r"(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?", text)
    if m:
        try:
            return date(today.year, int(m.group(1)), int(m.group(2))).isoformat()
        except ValueError:
            pass
    if "后天" in text:
        return (today + timedelta(days=2)).isoformat()
    if "明天" in text:
        return (today + timedelta(days=1)).isoformat()
    m = re.search(r"周([一二三四五六日天])", text)
    if m:
        offset = {"一": 0, "二": 1, "三": 2, "四": 3, "五": 4, "六": 5, "日": 6, "天": 6}[m.group(1)]
        delta = (offset - today.weekday()) % 7 or 7
        return (today + timedelta(days=delta)).isoformat()
    return None


def parse_request(text: str):
    """返回 (request: dict, questions: list)。questions 为交互模式下需要追问的问题。"""
    notes, questions = [], []

    destination = _extract_destination(text)
    if not destination:
        destination = "三亚"
        questions.append("请问目的地是哪里？（未识别到目的地，已默认使用三亚演示）")

    days = _extract_days(text) or 3
    budget = _extract_budget(text) or 3000
    adults, children = _extract_people(text)
    if adults is None:
        adults, children = 2, 0
        notes.append("未识别人数，默认 2 人")
    depart_city = _extract_depart(text) or destination
    if depart_city == destination:
        notes.append("出发地与目的地相同，按市内交通估算")
    start_date = _extract_start_date(text)
    if not start_date:
        start_date = (date.today() + timedelta(days=1)).isoformat()
        notes.append("未识别出行日期，默认明天出发")

    preferences = [name for name, kws in PREF_KEYWORDS.items() if any(k in text for k in kws)]

    request = {
        "goal": text,
        "destination": destination,
        "depart_city": depart_city,
        "days": days,
        "budget": budget,
        "adults": adults,
        "children": children,
        "people": adults + children,
        "start_date": start_date,
        "preferences": preferences,
        "kb_covered": destination in KNOWN_CITIES,
        "notes": notes,
    }
    return request, questions
