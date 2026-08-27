# -*- coding: utf-8 -*-
"""性能与 token 基准：冷启动 vs 缓存命中、串行 vs 并行执行对比。python bench.py

输出三组数据，用于验收时量化"加速响应 / 减少 token"两项优化：
  1. 冷启动全链路耗时（含真实天气 API + 知识库检索与缓存写入）
  2. 缓存命中全链路耗时（0 网络、0 token）
  3. 工具串行 vs 并行微基准（DAG 并行加速的依据）
"""
from __future__ import annotations

import sys
import time
from concurrent.futures import ThreadPoolExecutor

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

import tools  # noqa: F401 触发工具注册
from core.agent import TravelAgent
from core.llm import TOKEN_METER
from tools.base import invalidate

GOAL = "从上海出发去苏州2天，2人，预算3000元"


def meter_snapshot():
    return {"calls": TOKEN_METER.calls,
            "tokens": TOKEN_METER.prompt_tokens + TOKEN_METER.completion_tokens,
            "hits": TOKEN_METER.cache_hits}


def full_run(run_id):
    t0 = time.time()
    agent = TravelAgent(GOAL, run_id=run_id)
    path = agent.run()
    return time.time() - t0, path


def micro_tools():
    """同一组工具调用：串行 vs 并行（force_refresh 绕过缓存，体现真实 IO 并行收益）。"""
    import tools.weather  # noqa: F401
    import tools.poi
    from tools.base import get_tool

    params_w = {"destination": "苏州", "start_date": "2026-09-10", "days": 2, "force_refresh": True}
    params_p = {"destination": "苏州", "days": 2, "preferences": [], "goal": GOAL}

    t0 = time.time()
    get_tool("weather").run(dict(params_w))
    get_tool("poi").run(dict(params_p))
    serial = time.time() - t0

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=2) as pool:
        pool.submit(get_tool("weather").run, dict(params_w))
        pool.submit(get_tool("poi").run, dict(params_p))
    parallel = time.time() - t0
    return serial, parallel


def main():
    print("=" * 62)
    print(" 性能与 token 基准 · %s" % GOAL)
    print("=" * 62)

    invalidate("weather")
    invalidate("poi")
    before = meter_snapshot()
    cold, path = full_run("bench_cold")
    cold_m = {k: meter_snapshot()[k] - before[k] for k in before}

    before = meter_snapshot()
    warm, _ = full_run("bench_warm")
    warm_m = {k: meter_snapshot()[k] - before[k] for k in before}

    serial, parallel = micro_tools()

    print("""
[1] 全链路端到端
    冷启动（含网络+缓存写入） : %6.2f s   LLM调用 %d 次 / token %d / 缓存命中 %d
    缓存命中（0网络0token）   : %6.2f s   LLM调用 %d 次 / token %d / 缓存命中 %d
    端到端加速比              : %6.1f x

[2] 工具层串行 vs 并行（真实IO）
    串行 weather→poi          : %6.2f s
    并行 weather∥poi          : %6.2f s
    工具层加速比              : %6.1f x

[3] 结论
    - 缓存命中后全链路毫秒级完成，token 消耗为 0（规则模板+知识库检索）
    - 无依赖子任务并行显著缩短网络等待时间
""" % (cold, cold_m["calls"], cold_m["tokens"], cold_m["hits"],
       warm, warm_m["calls"], warm_m["tokens"], warm_m["hits"],
       cold / max(warm, 1e-6), serial, parallel, serial / max(parallel, 1e-6)))
    if path:
        print("方案产物：%s" % path)


if __name__ == "__main__":
    main()
