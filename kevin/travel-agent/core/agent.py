# -*- coding: utf-8 -*-
"""调度执行器（Agent 主循环）。

特性：
1. 断点续跑：每个子任务完成后写 checkpoint，resume 时跳过已完成步骤；
2. 并行加速：DAG 中无依赖的子任务（天气/景点检索）并行执行；
3. 失败重试 + Mock 兜底：工具异常自动重试，仍失败则由各工具内部 Mock 数据兜底，流程不中断；
4. token 可观测：全程 TokenMeter 记账，报告文档附带运行统计。
"""
from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

from config import MAX_WORKERS, TOOL_RETRY
from core.checkpoint import Checkpoint
from core.llm import TOKEN_METER
from core.nlu import is_travel_intent, parse_request
from core.planner import build_dag
from tools.base import get_tool


def log(tag, msg):
    print("%s [%s] %s" % (datetime.now().strftime("%H:%M:%S"), tag, msg), flush=True)


class TravelAgent(object):
    def __init__(self, goal: str, run_id: str = None):
        self.goal = goal
        self.run_id = run_id or ("run_" + datetime.now().strftime("%Y%m%d_%H%M%S"))
        self.ckpt = None
        self.executed = []          # 本次实际执行的任务（断点续跑演示/测试用）
        self.task_seconds = {}
        self.report_path = None
        self._failed_tasks = set()  # 本次运行中重试后仍失败的任务

    # ---------------- 入口 ----------------
    def run(self, resume=False, max_steps=None, fail_at=None, force_refresh=False):
        if resume:
            self.ckpt = Checkpoint.load(self.run_id)
            self.goal = self.ckpt.state["goal"]
            done = sorted(self.ckpt.completed())
            log("RESUME", "载入断点 %s，已完成 %d 步：%s" % (self.run_id, len(done), "→".join(done) or "无"))
        else:
            self.ckpt = Checkpoint(self.run_id, self.goal)

        t0 = time.time()

        # ---- 子任务0：需求解析（NLU）----
        if "nlu" not in self.ckpt.completed():
            self._run_nlu()
        else:
            log("RESUME", "跳过已完成步骤：nlu")
        request = self.ckpt.result("nlu")

        # ---- 检查意图（非旅行输入直接拒绝，避免无意义长任务）----
        if not is_travel_intent(self.goal):
            log("NLU", "输入不像旅行规划需求，Agent 拒绝执行：%s" % self.goal)
            return None

        # ---- DAG 执行循环 ----
        dag = build_dag(request)
        log("PLAN", "拆解出 %d 个子任务：%s" % (len(dag), " → ".join(t["name"] for t in dag)))

        while True:
            completed = set(self.ckpt.completed())
            pending = [t for t in dag if t["name"] not in completed]
            if not pending:
                break
            ready = [t for t in pending
                     if all(d in completed for d in t["deps"]) and t["name"] not in self._failed_tasks]
            if not ready:
                log("PLAN", "无待执行任务（存在失败步骤），本轮终止；可 --resume 重试")
                break
            if max_steps is not None and len(completed) >= max_steps:
                log("STEP", "已达到单次步数上限 %d，主动暂停并保存断点：%s"
                    % (max_steps, self.run_id))
                return None

            if len(ready) > 1:
                log("PLAN", "并行执行无依赖子任务：%s（线程池加速）" % ", ".join(t["name"] for t in ready))
                with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
                    futures = [pool.submit(self._exec_task, t["name"], t["desc"], fail_at, force_refresh)
                               for t in ready]
                    for f in futures:
                        f.result()
            else:
                t = ready[0]
                self._exec_task(t["name"], t["desc"], fail_at, force_refresh)

        # ---- 汇总 ----
        elapsed = time.time() - t0
        report = self.ckpt.result("report")
        if report:
            self.report_path = report["path"]
            log("DONE", "全链路完成，耗时 %.1fs，方案文件：%s" % (elapsed, report["path"]))
        else:
            log("DONE", "本轮结束（有步骤未完成），耗时 %.1fs；可用 --resume %s 续跑" % (elapsed, self.run_id))
        return self.report_path

    # ---------------- 步骤实现 ----------------
    def _run_nlu(self):
        t0 = time.time()
        log("TASK", "▶ nlu 开始：意图识别 + 实体抽取 + 参数补全")
        request, questions = parse_request(self.goal)
        self.ckpt.mark("nlu", request)
        self.executed.append("nlu")
        self.task_seconds["nlu"] = time.time() - t0
        log("TASK", "✔ nlu 完成：%s %d日 %d人 预算%d元 出发地%s 偏好%s%s"
            % (request["destination"], request["days"], request["people"], request["budget"],
               request["depart_city"], "、".join(request["preferences"]) or "无",
               ("；待确认：" + "；".join(questions)) if questions else ""))

    def _exec_task(self, name, desc, fail_at, force_refresh):
        t0 = time.time()
        log("TASK", "▶ %s 开始：%s" % (name, desc))
        result = self._call_with_retry(name, fail_at, force_refresh)
        if result is None:
            self._failed_tasks.add(name)
            log("TASK", "✖ %s 重试后仍失败，标记跳过（Mock 兜底也未生效）" % name)
            return
        self.ckpt.mark(name, result)
        self.executed.append(name)
        self.task_seconds[name] = time.time() - t0
        log("TASK", "✔ %s 完成，耗时 %.2fs" % (name, self.task_seconds[name]))

    def _call_with_retry(self, name, fail_at, force_refresh):
        last_err = None
        for attempt in range(1, TOOL_RETRY + 1):
            try:
                if fail_at and fail_at == name and attempt == 1:
                    raise RuntimeError("演示注入故障：模拟第 %d 步网络异常" % 1)
                params = self._params(name, force_refresh)
                return get_tool(name).run(params)
            except Exception as e:  # noqa: BLE001 重试后仍失败由上层兜底
                last_err = e
                log("TASK", "! %s 第 %d 次尝试失败：%s" % (name, attempt, e))
        log("TASK", "! %s 已重试 %d 次，最后错误：%s" % (name, TOOL_RETRY, last_err))
        return None

    # ---------------- 各子任务的参数组装（依赖上游结果）----------------
    def _params(self, name, force_refresh):
        req = self.ckpt.result("nlu")
        r = lambda n: self.ckpt.result(n)  # noqa: E731
        if name == "weather":
            return {"destination": req["destination"], "start_date": req["start_date"],
                    "days": req["days"], "force_refresh": force_refresh}
        if name == "poi":
            return {"destination": req["destination"], "days": req["days"],
                    "preferences": req["preferences"], "goal": req["goal"]}
        if name == "budget":
            p = r("poi")
            return {"request": req, "hotels": p["hotels"], "foods": p["foods"],
                    "attractions": p["attractions"]}
        if name == "itinerary":
            return {"request": req, "weather": r("weather"), "poi": r("poi"), "budget": r("budget")}
        if name == "validate":
            return {"request": req, "weather": r("weather"), "poi": r("poi"),
                    "budget": r("budget"), "itinerary": r("itinerary")}
        if name == "report":
            return {"request": req,
                    "results": {n: r(n) for n in ("weather", "poi", "budget", "validate")},
                    "stats": {"run_id": self.run_id, "task_seconds": self.task_seconds,
                              "executed": self.executed, "token_report": TOKEN_METER.report()}}
        raise ValueError("未知子任务: %s" % name)
