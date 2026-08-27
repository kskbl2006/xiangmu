# -*- coding: utf-8 -*-
"""验收演示脚本：按回车逐步演示 5 个核心特性（约 3 分钟）。

  python demo.py            # 交互模式（推荐验收时用）
  python demo.py --auto     # 自动模式（无停顿连跑，录屏/自测用）
"""
from __future__ import annotations

import subprocess
import sys
import os

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(HERE, "main.py")
AUTO = "--auto" in sys.argv
GOAL = "北京出发去三亚5天，预算5000元，2大1小"


def step(title, desc):
    print("\n" + "=" * 62)
    print(" ▶ %s" % title)
    print("   %s" % desc)
    print("=" * 62)
    if not AUTO:
        try:
            input("   按回车开始本节演示（Ctrl+C 退出）...")
        except KeyboardInterrupt:
            print("\n演示中断")
            sys.exit(0)


def run(*args):
    cmd = [sys.executable, MAIN] + list(args)
    print("\n$ python main.py %s\n" % " ".join(a if " " not in a else '"%s"' % a for a in args))
    subprocess.call(cmd)


def main():
    print("智能旅行规划助手 Agent · 验收演示" + ("（自动模式）" if AUTO else ""))
    print("示例目标：%s" % GOAL)

    step("1/5 完整闭环", "一句话输入 → 7 步子任务自动拆解 → 并行工具调用 → 产出《旅行方案》md+docx")
    run(GOAL)

    step("2/5 完成情况检查", "--status 查看所有运行总览与逐步详情（方案是否生成、自检修复记录）")
    run("--status")
    run("--status", "test_full")

    step("3/5 断点续跑", "先 --step 3 跑 3 步暂停，再 --resume 从断点续跑，已完成步骤不重复执行")
    run("--step", "3", "帮我规划西安3日游，2人，预算7000元")
    run("--status")  # 从总览里能看到 3/7 的未完成运行
    latest = _latest_partial_run()
    if latest:
        run("--resume", latest)

    step("4/5 故障自愈", "--fail-at weather 注入网络故障 → 自动重试成功，流程不中断")
    run("--fail-at", "weather", "帮我规划苏州2日游，2人，预算4000元")

    step("5/5 缓存加速与 token 优化", "同一目标再跑一次：工具缓存命中，秒级完成、0 次 LLM 调用")
    run(GOAL)

    print("\n演示完毕。历史运行与方案文档见 workspace/runs/，完成情况：python main.py --status")


def _latest_partial_run():
    """找出最近一个未完成的 run_id 供续跑演示。"""
    from core.checkpoint import Checkpoint
    from config import RUNS_DIR
    for r in sorted(RUNS_DIR.iterdir(), reverse=True):
        try:
            ck = Checkpoint.load(r.name)
        except Exception:
            continue
        if len(ck.completed()) < 7 and r.name != "test_full":
            return r.name
    return None


if __name__ == "__main__":
    main()
