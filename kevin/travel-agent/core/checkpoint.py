# -*- coding: utf-8 -*-
"""断点续跑（Checkpoint）：每个子任务完成后状态原子落盘，支持 --resume 续跑。

落盘内容：workspace/runs/<run_id>/checkpoint.json
  { run_id, goal, created_at, tasks: { task_name: {status, result, finished_at} } }
同时把每个子任务的原始结果存为 step_<name>.json，便于调试与演示中间过程。
"""
from __future__ import annotations

import json
import os
import threading
from datetime import datetime
from pathlib import Path

from config import RUNS_DIR


class Checkpoint(object):
    def __init__(self, run_id: str, goal: str):
        self.run_id = run_id
        self.dir = RUNS_DIR / run_id
        self.dir.mkdir(parents=True, exist_ok=True)
        self.path = self.dir / "checkpoint.json"
        self._lock = threading.Lock()  # 并行子任务同时 mark 时的写互斥
        self.state = {
            "run_id": run_id,
            "goal": goal,
            "created_at": datetime.now().isoformat(timespec="seconds"),
            "tasks": {},
        }

    # ---- 写 ----
    def mark(self, task: str, result):
        with self._lock:
            self.state["tasks"][task] = {
                "status": "done",
                "result": result,
                "finished_at": datetime.now().isoformat(timespec="seconds"),
            }
            self._flush(task)

    def _flush(self, task: str):
        """原子写：先写临时文件再替换，避免中途崩溃损坏断点。调用方需持有 _lock。"""
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.state, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(str(tmp), str(self.path))
        # 中间产物同步落盘，便于调试与演示
        step_file = self.dir / ("step_%s.json" % task)
        step_file.write_text(
            json.dumps(self.state["tasks"][task], ensure_ascii=False, indent=2), encoding="utf-8")

    # ---- 读 ----
    def completed(self):
        return [n for n, t in self.state["tasks"].items() if t["status"] == "done"]

    def result(self, task: str):
        t = self.state["tasks"].get(task)
        return t["result"] if t else None

    @classmethod
    def load(cls, run_id: str) -> "Checkpoint":
        path = RUNS_DIR / run_id / "checkpoint.json"
        if not path.exists():
            raise FileNotFoundError("未找到运行记录 %s（查看 workspace/runs/ 目录）" % run_id)
        ck = cls.__new__(cls)
        ck.run_id = run_id
        ck.dir = RUNS_DIR / run_id
        ck.path = path
        ck._lock = threading.Lock()
        ck.state = json.loads(path.read_text(encoding="utf-8"))
        return ck
