# -*- coding: utf-8 -*-
"""工具基座：统一注册（Tool Registry）+ 磁盘缓存（TTL）+ 缓存失效。

所有工具实现 run(params) -> dict（结构化 JSON），由 Agent 在执行循环中统一调度。
缓存命中 = 0 token、0 网络等待，是"加速响应 + 减少 token 消耗"的主要手段之一。
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import time
from pathlib import Path

from config import CACHE_DIR
from core.llm import TOKEN_METER

REGISTRY = {}


def tool(name: str):
    """类装饰器：注册工具实例到全局 REGISTRY。"""
    def deco(cls):
        REGISTRY[name] = cls()
        return cls
    return deco


def get_tool(name: str):
    if name not in REGISTRY:
        raise KeyError("工具未注册: %s" % name)
    return REGISTRY[name]


def _cache_path(key: str) -> Path:
    digest = hashlib.md5(key.encode("utf-8")).hexdigest()[:12]
    safe = re.sub(r"[^0-9A-Za-z_-]+", "_", key.split(":")[0])
    return CACHE_DIR / ("%s_%s.json" % (safe, digest))


def cache_get(key: str, ttl: int):
    p = _cache_path(key)
    if p.exists():
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            if time.time() - data["ts"] <= ttl:
                TOKEN_METER.cache_hits += 1
                TOKEN_METER.saved_tokens += 300  # 估算：一次工具结果进入下游 prompt 的 token 量
                return data["data"]
        except Exception:
            pass
    return None


def cache_set(key: str, data):
    p = _cache_path(key)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps({"ts": time.time(), "data": data}, ensure_ascii=False), encoding="utf-8")
    os.replace(str(tmp), str(p))


def invalidate(prefix: str):
    """按前缀清除缓存（定时任务刷新天气时使用）。"""
    for p in CACHE_DIR.glob(prefix + "_*.json"):
        try:
            p.unlink()
        except OSError:
            pass
