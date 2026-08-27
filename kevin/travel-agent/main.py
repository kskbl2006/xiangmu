# -*- coding: utf-8 -*-
"""智能旅行规划助手 Agent —— 命令行入口。

快速上手：
  python main.py "北京出发去三亚5天，预算5000元，2大1小"          # 一句话 → 完整方案
  python main.py --list-runs                                      # 查看历史运行（找 run_id）
  python main.py --step 3 "..."                                   # 只跑前3步后暂停（演示断点）
  python main.py --resume run_20260826_103000                     # 断点续跑
  python main.py --fail-at weather "..."                          # 演示故障注入+自动重试
  python main.py --schedule "*/30 * * * *" "..."                  # 定时刷新方案
  python main.py --schedule "10m" "..." --max-runs 2              # 定时演示（最多2轮）
"""
from __future__ import annotations

import argparse
import os
import sys
import time

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

import config  # noqa: F401 触发目录创建
from core.agent import TravelAgent, log
from core.llm import TOKEN_METER
from core.scheduler import run_scheduled


def list_runs():
    from config import RUNS_DIR
    rows = sorted(RUNS_DIR.iterdir()) if RUNS_DIR.exists() else []
    if not rows:
        print("暂无运行记录（workspace/runs/ 为空）")
        return
    print("历史运行：")
    for r in rows[-10:]:
        print("  %s" % r.name)
    print("查看某次运行完成情况：python main.py --status <run_id>")


PIPELINE_ORDER = ["nlu", "weather", "poi", "budget", "itinerary", "validate", "report"]


def show_status(run_id=None):
    """检查运行完成情况：不带参数则总览所有历史运行。"""
    from core.checkpoint import Checkpoint
    from config import RUNS_DIR
    rows = sorted(RUNS_DIR.iterdir()) if RUNS_DIR.exists() else []
    if not rows:
        print("暂无运行记录")
        return
    if run_id is None:  # 总览
        print("%-24s %-8s %-10s %s" % ("RUN_ID", "步骤", "方案", "目标"))
        for r in rows[-15:]:
            try:
                ck = Checkpoint.load(r.name)
            except Exception:
                continue
            done = set(ck.completed())
            report = ck.result("report")
            ok = "已生成" if (report and os.path.exists(report["path"])) else "未生成"
            goal = ck.state["goal"][:24] + ("…" if len(ck.state["goal"]) > 24 else "")
            print("%-24s %d/7      %-10s %s" % (r.name, len(done), ok, goal))
        print("详情：python main.py --status <run_id>")
        return
    ck = Checkpoint.load(run_id)
    done = set(ck.completed())
    print("运行：%s" % run_id)
    print("目标：%s" % ck.state["goal"])
    print("创建时间：%s" % ck.state["created_at"])
    print("步骤完成情况（%d/7）：" % len(done))
    for name in PIPELINE_ORDER:
        print("  [%s] %s" % ("✔" if name in done else "…", name))
    report = ck.result("report")
    if report:
        exists = os.path.exists(report["path"])
        print("方案文档：%s（%s）" % (report["path"], "已生成" if exists else "⚠ 文件缺失"))
    else:
        print("方案文档：未生成 → 续跑：python main.py --resume %s" % run_id)
    vd = ck.result("validate")
    if vd:
        print("自检结果：修复 %d 项，风险 %d 项" % (len(vd.get("fixes", [])), len(vd.get("issues", []))))
        for f in vd.get("fixes", []):
            print("  ✅ " + f)
        for i in vd.get("issues", []):
            print("  ⚠ " + i)


def main():
    ap = argparse.ArgumentParser(description="智能旅行规划助手 Agent")
    ap.add_argument("goal", nargs="?", help="一句话旅行需求")
    ap.add_argument("--resume", metavar="RUN_ID", help="从断点继续运行")
    ap.add_argument("--step", type=int, default=None, help="本次最多执行到第 N 步（演示断点续跑）")
    ap.add_argument("--fail-at", dest="fail_at", default=None, help="故障注入：指定首次执行失败的子任务名")
    ap.add_argument("--schedule", default=None, help="定时规则：'*/30 * * * *' / '10m' / '08:00'")
    ap.add_argument("--max-runs", dest="max_runs", type=int, default=None, help="定时模式最多触发轮数")
    ap.add_argument("--status", nargs="?", const="__all__", metavar="RUN_ID",
                    help="检查完成情况：不带参数查看所有运行总览，指定 run_id 查看详情")
    args = ap.parse_args()

    llm_mode = "真实LLM(%s)" % config.LLM_MODEL if (config.LLM_API_KEY and config.LLM_BASE_URL) else "MockLLM(离线兜底)"
    print("=" * 62)
    print(" 智能旅行规划助手 Agent · 自主规划型 · LLM引擎：%s" % llm_mode)
    print("=" * 62)

    if args.status is not None:
        show_status(None if args.status == "__all__" else args.status)
        return

    if args.schedule:
        if not args.goal:
            ap.error("--schedule 需要同时提供一句话目标")
        run_scheduled(args.schedule, args.goal, max_runs=args.max_runs)
        return

    if args.resume:
        t0 = time.time()
        agent = TravelAgent(args.goal or "", run_id=args.resume)
        path = agent.run(resume=True, max_steps=args.step, fail_at=args.fail_at)
    elif args.goal:
        t0 = time.time()
        agent = TravelAgent(args.goal)
        path = agent.run(max_steps=args.step, fail_at=args.fail_at)
    else:
        ap.print_help()
        list_runs()
        return

    print("-" * 62)
    print("总耗时 %.1fs | 实际执行步骤：%s" % (time.time() - t0, " → ".join(agent.executed) or "无"))
    print(TOKEN_METER.report())
    if path:
        print("方案文档：%s" % path)
    elif args.step:
        print("已按 --step %d 暂停，续跑：python main.py --resume %s" % (args.step, agent.run_id))
    print("=" * 62)


if __name__ == "__main__":
    main()
