# -*- coding: utf-8 -*-
"""
GagneFlow RAG 召回率对比评测: 向量检索 vs 纯关键词匹配

用法: python recall_compare.py
环境: DASHSCOPE_API_KEY + Milvus(127.0.0.1:19530) + jieba
链路: 同一评测集(mrr_eval_set.json 40条) 分别跑两条基线, 输出 MRR/HitRate@3/@5 对比
"""
import os
import json
import jieba
from mrr_eval import get_collection, load_chunks, parse_chunk, embed, EVAL_SET

# 功能词停用表（去结构化虚词，保留学科/年级/章节/知识点实体词）
STOP_WORDS = set("在 阶段 部分 的 什么 和 与 及 是 应该 要求 教学 内容 怎么 安排 学习 掌握 理解 运用 这些 这类 个 一".split())


def tokenize(text):
    return [t for t in jieba.lcut(text) if t.strip() and t not in STOP_WORDS]


def kw_retrieve(query_tokens, chunks_tokenized):
    """关键词匹配: 按查询词对分片词的覆盖率排序, 返回前 10 个分片 id"""
    qset = set(query_tokens)
    if not qset:
        return []
    scored = []
    for cid, cset in chunks_tokenized:
        hit = len(qset & cset)
        if hit > 0:
            scored.append((cid, hit / float(len(qset))))
    scored.sort(key=lambda x: -x[1])
    return [cid for cid, _ in scored[:10]]


def compute_metrics(ranks, total):
    mrrs, hit1, hit3, hit5 = [], 0, 0, 0
    for rank in ranks:
        if rank is None:
            mrrs.append(0.0)
            continue
        mrrs.append(1.0 / rank)
        if rank == 1:
            hit1 += 1
        if rank <= 3:
            hit3 += 1
        if rank <= 5:
            hit5 += 1
    n = total
    return {
        "MRR": round(sum(mrrs) / n, 4),
        "HitRate@1": round(hit1 / n, 4),
        "HitRate@3": round(hit3 / n, 4),
        "HitRate@5": round(hit5 / n, 4),
    }


def main():
    coll = get_collection()
    chunks = load_chunks(coll)  # [(id, content)]
    eval_data = json.load(open(EVAL_SET, encoding="utf-8"))
    queries = eval_data["queries"]
    print("k12 chunks:", len(chunks), "| eval queries:", len(queries))

    # expected 标识 -> 当前 id 映射
    index = {}
    for cid, content in chunks:
        info = parse_chunk(content)
        if info:
            index["%s|%s|%s" % (info["path"], info["chapter"], info["kp"])] = cid

    # 分片词集缓存（关键词基线用）
    chunks_tokenized = []
    for cid, content in chunks:
        tokens = set(tokenize(content))
        chunks_tokenized.append((cid, tokens))

    # 1) 关键词匹配基线
    kw_ranks = []
    for item in queries:
        expected = index.get(item["expected"])
        if expected is None:
            kw_ranks.append(None)
            continue
        qt = tokenize(item["query"])
        top10 = kw_retrieve(qt, chunks_tokenized)
        rank = top10.index(expected) + 1 if expected in top10 else None
        kw_ranks.append(rank)
    kw_metrics = compute_metrics(kw_ranks, len(queries))
    print("\n=== 关键词匹配基线（jieba 分词 + 词覆盖率排序）===")
    for k, v in kw_metrics.items():
        print("%s: %s" % (k, v))

    # 2) 向量检索基线（真实 embedding + Milvus）
    vec_ranks = []
    for item in queries:
        expected = index.get(item["expected"])
        if expected is None:
            vec_ranks.append(None)
            continue
        try:
            vec = embed(item["query"])
            res = coll.search(data=[vec], anns_field="vector",
                              param={"metric_type": "L2", "params": {"nprobe": 16}},
                              limit=10, output_fields=["id"])
            ids = [str(h.id) for h in res[0]]
            rank = ids.index(expected) + 1 if expected in ids else None
            vec_ranks.append(rank)
        except Exception as e:
            print("vec query fail:", item["query"][:30], str(e)[:80])
            vec_ranks.append(None)
    vec_metrics = compute_metrics(vec_ranks, len(queries))
    print("\n=== 向量检索（text-embedding-v4 + Milvus IVF_FLAT nprobe=16）===")
    for k, v in vec_metrics.items():
        print("%s: %s" % (k, v))

    # 3) 对比
    print("\n=== 对比与提升 ===")
    for k in ["MRR", "HitRate@3", "HitRate@5"]:
        d = vec_metrics[k] - kw_metrics[k]
        print("%s: 关键词 %.4f -> 向量 %.4f (%+.1fpp / %+.0f%%)"
              % (k, kw_metrics[k], vec_metrics[k], d * 100, (d / kw_metrics[k] * 100 if kw_metrics[k] else 0)))


if __name__ == "__main__":
    main()
