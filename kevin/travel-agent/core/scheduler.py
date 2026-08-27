# -*- coding: utf-8 -*-
"""定时任务调度器：按固定间隔 / 每日固定时间自动重跑规划（刷新天气、重出方案）。

用法：
  python main.py --schedule "*/30 * * * *" "北京出发去三亚5天，预算5000元，2大1小"   # 每 30 分钟
  python main.py --schedule "08:00" "..."                                         # 每天 08:00
  python main.py --schedule "10m" "..." --max-runs 3                              # 每 10 分钟，最多 3 次（演示）

每次触发会：清空天气缓存（拿最新预报）→ 新建一轮 run（多轮任务）→ 景点知识库缓存
继续命中（演示"缓存节省 token"），日志追加到 workspace/schedule.log。
"""
from __future__ import annotations

import time
from datetime import datetime, timedelta

from config import WORKSPACE
from tools.base import invalidate


def parse_pattern(pattern: str):
    """返回 ("interval", 分钟) 或 ("daily", 时, 分)。"""
    p = pattern.strip()
    if p.endswith("m") and p[:-1].isdigit():          # "10m"
        return ("interval", max(1, int(p[:-1])))
    if " " in p and p.startswith("*/"):               # "*/30 * * * *"
        return ("interval", max(1, int(p.split()[0][2:])))
    try:                                              # "08:00"
        hh, mm = p.split(":")
        return ("daily", int(hh), int(mm))
    except ValueError:
        raise ValueError("无法解析定时规则：%s（支持 '*/N * * * *'、'Nm'、'HH:MM'）" % pattern)


def next_fire(pattern: str, now: datetime) -> datetime:
    kind = parse_pattern(pattern)
    if kind[0] == "interval":
        return now + timedelta(minutes=kind[1])
    _, hh, mm = kind
    fire = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
    if fire <= now:
        fire += timedelta(days=1)
    return fire


def _append_log(line: str):
    path = WORKSPACE / "schedule.log"
    with open(str(path), "a", encoding="utf-8") as f:
        f.write(line + "\n")


def run_scheduled(pattern: str, goal: str, max_runs=None, **agent_kwargs):
    from core.agent import TravelAgent, log  # 延迟导入避免循环依赖

    log("CRON", "定时任务启动：规则=%s，目标=%s（Ctrl+C 退出）" % (pattern, goal))
    runs = 0
    while max_runs is None or runs < max_runs:
        fire = next_fire(pattern, datetime.now())
        log("CRON", "第 %d 轮将在 %s 触发" % (runs + 1, fire.strftime("%Y-%m-%d %H:%M:%S")))
        while datetime.now() < fire:
            time.sleep(min(1, (fire - datetime.now()).total_seconds()))
        invalidate("weather")  # 定时刷新：天气缓存过期，景点缓存保留
        log("CRON", "触发第 %d 轮规划（已清空天气缓存，重取最新预报）" % (runs + 1))
        agent = TravelAgent(goal)
        path = agent.run(force_refresh=True)
        _append_log("[%s] run=%s report=%s" % (
            datetime.now().isoformat(timespec="seconds"), agent.run_id, path or "未生成"))
        runs += 1
    log("CRON", "已达 max-runs=%d，调度结束" % max_runs)
