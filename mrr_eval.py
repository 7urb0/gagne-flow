# -*- coding: utf-8 -*-
"""
GagneFlow RAG 真实评测脚本（可复现）

用法:
    python mrr_eval.py             # 评测集存在则直接评测，不存在则先生成再评测
    python mrr_eval.py --gen-only  # 只生成评测集（mrr_eval_set.json）

环境:
    - 需要 DASHSCOPE_API_KEY 环境变量
    - Milvus 运行在 127.0.0.1:19530, collection=biz, 含 k12_curriculum 分片

链路: 评测集(40条自然语言问题, 每条标注期望分片)
      -> DashScope text-embedding-v4 向量化(1024维)
      -> Milvus IVF_FLAT 检索(nprobe=16, L2, top-10)
      -> 每条查询期望分片的倒数排名 -> MRR = 平均倒数排名
"""
import os
import re
import json
import random
import sys
import urllib.request
import urllib.error

KEY = os.environ.get("DASHSCOPE_API_KEY", "")
MODEL = "text-embedding-v4"
BASE = os.path.dirname(os.path.abspath(__file__))
EVAL_SET = os.path.join(BASE, "mrr_eval_set.json")
MILVUS_HOST, MILVUS_PORT = "127.0.0.1", "19530"


def get_collection():
    from pymilvus import connections, Collection
    connections.connect(alias="default", host=MILVUS_HOST, port=MILVUS_PORT)
    c = Collection("biz")
    c.load()
    return c


def embed(text):
    if not KEY:
        raise RuntimeError("DASHSCOPE_API_KEY not set")
    body = json.dumps({"model": MODEL, "input": {"texts": [text]}}).encode("utf-8")
    req = urllib.request.Request(
        "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding",
        data=body,
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return data["output"]["embeddings"][0]["embedding"]
    except urllib.error.HTTPError as e:
        raise RuntimeError("embedding API HTTP %d: %s" % (e.code, e.read().decode("utf-8")[:200]))


def load_chunks(coll):
    rows = coll.query(expr='metadata["_source"] == "k12_curriculum"',
                      output_fields=["id", "content"], limit=5000)
    return [(str(r["id"]), r.get("content") or "") for r in rows]


def parse_chunk(content):
    """content: '<路径> <章节名>：<内容要求> 知识点：<知识点名>' -> dict(path, chapter, kp)"""
    if " 知识点：" not in content:
        return None
    left, kp = content.split(" 知识点：", 1)
    if "：" not in left:
        return None
    head, _req = left.split("：", 1)
    head = head.strip()
    parts = head.rsplit(" ", 1)
    if len(parts) != 2:
        return None
    return {"path": parts[0].strip(), "chapter": parts[1].strip(), "kp": kp.strip()}


def make_query(info):
    return "在%s阶段，%s部分的教学内容和要求是什么？" % (info["path"], info["chapter"])


def ensure_eval_set(chunks):
    if os.path.exists(EVAL_SET):
        return json.load(open(EVAL_SET, encoding="utf-8"))
    items = []
    for cid, content in chunks:
        info = parse_chunk(content)
        if info:
            items.append({
                "id": cid,
                "query": make_query(info),
                "expected": "%s|%s|%s" % (info["path"], info["chapter"], info["kp"]),
            })
    random.seed(42)
    random.shuffle(items)
    items = items[:40]
    data = {
        "description": "GagneFlow RAG 评测集（自动构造自然语言变体问题, seed=42 抽样 40 条）",
        "model": MODEL,
        "created": "2026-08-07",
        "n_chunks": len(chunks),
        "queries": items,
    }
    with open(EVAL_SET, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return data


def run_eval(coll, chunks, eval_data):
    # expected 标识 -> 当前 id 映射（抗重灌后 id 变化）
    index = {}
    for cid, content in chunks:
        info = parse_chunk(content)
        if info:
            index["%s|%s|%s" % (info["path"], info["chapter"], info["kp"])] = cid
    mrrs, hit1, hit3, hit5 = [], 0, 0, 0
    total = len(eval_data["queries"])
    for item in eval_data["queries"]:
        expected = index.get(item["expected"])
        if expected is None:
            mrrs.append(0.0)
            continue
        try:
            vec = embed(item["query"])
            res = coll.search(data=[vec], anns_field="vector",
                              param={"metric_type": "L2", "params": {"nprobe": 16}},
                              limit=10, output_fields=["id"])
            ids = [str(h.id) for h in res[0]]
        except Exception as e:
            print("query fail:", item["query"][:30], str(e)[:80])
            mrrs.append(0.0)
            continue
        if expected in ids:
            rank = ids.index(expected) + 1
            mrrs.append(1.0 / rank)
            if rank == 1:
                hit1 += 1
            if rank <= 3:
                hit3 += 1
            if rank <= 5:
                hit5 += 1
        else:
            mrrs.append(0.0)
    n = total
    return {
        "n": n,
        "MRR": round(sum(mrrs) / n, 4),
        "HitRate@1": round(hit1 / n, 4),
        "HitRate@3": round(hit3 / n, 4),
        "HitRate@5": round(hit5 / n, 4),
    }


if __name__ == "__main__":
    coll = get_collection()
    chunks = load_chunks(coll)
    print("k12 chunks:", len(chunks))
    eval_data = ensure_eval_set(chunks)
    print("eval set:", len(eval_data["queries"]), "queries ->", EVAL_SET)
    if "--gen-only" in sys.argv:
        sys.exit(0)
    result = run_eval(coll, chunks, eval_data)
    print("=== 评测结果 ===")
    for k, v in result.items():
        print("%s: %s" % (k, v))
