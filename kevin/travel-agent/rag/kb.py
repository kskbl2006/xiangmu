# -*- coding: utf-8 -*-
"""轻量级 RAG 检索（BM25-lite，纯标准库实现）。

- 中文按字符二元组（bigram）分词，无需外部分词依赖；
- relevance = 查询与文档的 bigram 重合度（0~1）；
- 供 poi 工具对本地旅行知识库做相关性排序。
"""
from __future__ import annotations

from collections import Counter
from typing import List


def bigrams(text: str) -> Counter:
    text = "".join(str(text).split())
    return Counter(text[i:i + 2] for i in range(max(len(text) - 1, 0)))


def relevance(query: str, doc: str) -> float:
    q, d = bigrams(query), bigrams(doc)
    if not q or not d:
        return 0.0
    hit = sum(min(c, d.get(g, 0)) for g, c in q.items())
    return hit / sum(q.values())


def search(query: str, docs: List[dict], text_keys=("title", "tags", "desc"), k: int = 5) -> List[dict]:
    scored = []
    for doc in docs:
        text = " ".join(str(doc.get(key, "")) for key in text_keys)
        scored.append((relevance(query, text), doc))
    scored.sort(key=lambda x: x[0], reverse=True)
    return [doc for score, doc in scored[:k] if score > 0]
