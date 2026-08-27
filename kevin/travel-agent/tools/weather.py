# -*- coding: utf-8 -*-
"""天气查询工具：Open-Meteo 真实 API（免密钥）→ 失败/超范围自动降级 Mock（确定性模拟）。"""
from __future__ import annotations

import json
import random
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta

from config import TOOL_TIMEOUT, WEATHER_CACHE_TTL
from tools.base import cache_get, cache_set, tool

CITY_COORDS = {
    "三亚": (18.25, 109.50), "上海": (31.23, 121.47), "北京": (39.90, 116.40),
    "成都": (30.57, 104.07), "杭州": (30.27, 120.16), "西安": (34.34, 108.94),
    "重庆": (29.56, 106.55), "广州": (23.13, 113.26), "南京": (32.04, 118.78),
    "苏州": (31.30, 120.58),
}
CITY_TEMP_BASE = {"三亚": (26, 32), "上海": (20, 28), "北京": (18, 30), "成都": (19, 27),
                  "杭州": (20, 29), "西安": (16, 30), "重庆": (24, 34), "广州": (25, 33),
                  "南京": (20, 31), "苏州": (20, 31)}

WMO_CODE = {0: "晴", 1: "晴", 2: "多云", 3: "多云", 45: "雾", 48: "雾",
            51: "小雨", 53: "小雨", 55: "小雨", 61: "小雨", 63: "中雨", 65: "大雨",
            71: "小雪", 73: "小雪", 75: "大雪", 80: "阵雨", 81: "阵雨", 82: "阵雨", 95: "雷雨"}

TIPS = {
    "晴": "紫外线较强，注意防晒补水",
    "多云": "体感舒适，适合户外活动",
    "雾": "能见度低，出行注意交通安全",
    "小雨": "携带雨具，建议优先安排室内景点",
    "中雨": "雨势较大，建议以室内活动为主",
    "大雨": "不建议户外行程，请灵活调整安排",
    "阵雨": "备好雨具，行程中预留弹性时间",
    "雷雨": "避免户外与水上项目，注意安全",
    "小雪": "注意保暖防滑",
    "大雪": "关注交通管制与航班动态",
}


@tool("weather")
class WeatherTool(object):
    name = "weather"

    def run(self, params: dict) -> dict:
        dest = params["destination"]
        start = params["start_date"]
        days = params["days"]
        key = "weather:%s:%s:%d" % (dest, start, days)
        if not params.get("force_refresh"):
            cached = cache_get(key, WEATHER_CACHE_TTL)
            if cached:
                cached["source"] = "cache"
                return cached
        data = self._real(dest, start, days)
        if data is None:
            data = self._mock(dest, start, days)
            data["source"] = "mock"
        else:
            data["source"] = "open-meteo"
        cache_set(key, data)
        return data

    # ---- 真实 API ----
    def _real(self, dest, start, days):
        if dest not in CITY_COORDS:
            return None
        lat, lon = CITY_COORDS[dest]
        start_d = datetime.strptime(start, "%Y-%m-%d").date()
        offset = (start_d - date.today()).days
        if offset < 0 or offset + days > 15:  # 预报仅支持 16 天内
            return None
        try:
            q = urllib.parse.urlencode({
                "latitude": lat, "longitude": lon,
                "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                "forecast_days": min(offset + days, 16), "timezone": "auto",
            })
            url = "https://api.open-meteo.com/v1/forecast?" + q
            with urllib.request.urlopen(url, timeout=TOOL_TIMEOUT) as resp:
                raw = json.loads(resp.read().decode("utf-8"))["daily"]
            out = []
            for i in range(offset, offset + days):
                d = start_d + timedelta(days=i - offset)
                code = int(raw["weather_code"][i])
                cond = WMO_CODE.get(code, "多云")
                rain = int(raw["precipitation_probability_max"][i] or 0)
                out.append({
                    "date": d.isoformat(),
                    "weekday": "周" + "一二三四五六日"[d.weekday()],
                    "cond": cond,
                    "temp_lo": round(raw["temperature_2m_min"][i]),
                    "temp_hi": round(raw["temperature_2m_max"][i]),
                    "rain_prob": rain,
                    "tip": TIPS.get(cond, ""),
                })
            return {"days": out}
        except Exception:
            return None

    # ---- Mock 兜底（确定性：同城市同日期结果一致，便于测试与演示）----
    def _mock(self, dest, start, days):
        lo, hi = CITY_TEMP_BASE.get(dest, (20, 28))
        rng = random.Random("%s-%s" % (dest, start))
        conds = ["晴", "晴", "多云", "多云", "小雨", "阵雨", "雷雨"]
        start_d = datetime.strptime(start, "%Y-%m-%d").date()
        out = []
        for i in range(days):
            d = start_d + timedelta(days=i)
            cond = rng.choice(conds)
            rain = {"晴": 5, "多云": 15, "小雨": 75, "阵雨": 60, "雷雨": 85}.get(cond, 20)
            out.append({
                "date": d.isoformat(),
                "weekday": "周" + "一二三四五六日"[d.weekday()],
                "cond": cond,
                "temp_lo": lo + rng.randint(-2, 1),
                "temp_hi": hi + rng.randint(-1, 2),
                "rain_prob": rain,
                "tip": TIPS.get(cond, ""),
            })
        return {"days": out}
