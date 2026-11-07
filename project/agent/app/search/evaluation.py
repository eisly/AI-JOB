from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urlsplit


def load_labels(path: str | Path | None) -> dict[str, Any] | None:
    if not path:
        return None
    target = Path(path)
    if not target.is_file():
        return None
    try:
        return json.loads(target.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _key(value: str) -> str:
    try:
        parsed = urlsplit(value)
        return f"{parsed.hostname or ''}{parsed.path}".rstrip("/").lower()
    except ValueError:
        return value.strip().lower()


def evaluate_ranked(items: Iterable[Any], labels: dict[str, Any] | None, *, k: int = 10) -> dict[str, Any]:
    """仅在提供人工标注集时计算 Recall@K / NDCG@K；否则明确标记未测量。"""
    if not labels:
        return {
            "status": "NOT_MEASURED",
            "reason": "缺少人工标注的相关岗位全集，未计算 Recall@K / NDCG@10",
        }
    relevant = {_key(str(value)) for value in labels.get("relevantUrls") or [] if value}
    judged = {_key(str(value)) for value in labels.get("judgedUrls") or [] if value}
    if not relevant:
        return {"status": "INVALID_LABELS", "reason": "标注集缺少 relevantUrls"}
    ranked = [item for item in items]
    top = ranked[:k]
    hits = 0
    dcg = 0.0
    for index, item in enumerate(top, start=1):
        url = _key(str(getattr(item, "source_url", "") or ""))
        if url in relevant:
            hits += 1
            dcg += 1.0 / math.log2(index + 1)
    ideal_count = min(len(relevant), k)
    idcg = sum(1.0 / math.log2(index + 1) for index in range(1, ideal_count + 1))
    if judged:
        # 标注集视为全量相关集；未判定项不应被当作“漏召回”以外的处理。
        recall = hits / len(relevant)
    else:
        recall = hits / len(relevant)
    return {
        "status": "MEASURED",
        "k": k,
        "relevantCount": len(relevant),
        "recallAtK": round(min(1.0, recall), 4),
        "ndcgAtK": round(dcg / idcg, 4) if idcg else 0.0,
        "precisionAtK": round(hits / len(top), 4) if top else 0.0,
        "labelSource": labels.get("source"),
    }