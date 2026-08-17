# -*- coding: utf-8 -*-
"""
新课标 MRR 评测 v3: 两环节递进（纯向量粗排 -> +qwen3-rerank 精排）
- 40 条自然语言变体问题（分层抽样, 覆盖主要学科）
- 环节 A: IVF_FLAT 粗排 top-15 -> MRR@15
- 环节 C: qwen3-rerank 精排 top-3 -> MRR@3 / HitRate@3
"""
import os
import sys
import json
import random
import time
import urllib.request

sys.path.insert(0, r'D:\project\agent\GagneFlow')
from mrr_eval import get_collection

CHUNKS = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_chunks.json'
KEY = os.environ.get('DASHSCOPE_API_KEY', '')


def embed(text, retries=3):
    body = json.dumps({'model': 'text-embedding-v4', 'input': {'texts': [text]}}).encode('utf-8')
    req = urllib.request.Request(
        'https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding',
        data=body, headers={'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json'})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.loads(resp.read().decode('utf-8'))
            return data['output']['embeddings'][0]['embedding']
        except Exception:
            if attempt == retries - 1:
                raise
            time.sleep(2 * (attempt + 1))


def rerank(query, documents, top_n=3, retries=3):
    body = json.dumps({'model': 'qwen3-rerank', 'input': {'query': query, 'documents': documents}}).encode('utf-8')
    req = urllib.request.Request(
        'https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank',
        data=body, headers={'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json'})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.loads(resp.read().decode('utf-8'))
            results = sorted(data['output']['results'], key=lambda r: -r['relevance_score'])
            return [r['index'] for r in results[:top_n]]
        except Exception:
            if attempt == retries - 1:
                raise
            time.sleep(2 * (attempt + 1))


def make_query(c):
    subj = c['subject']
    topic = (c['topic'] or '').replace('：', '').strip()
    module = c['module'] if c['module'] != '课程内容' else ''
    if subj == '课程方案':
        return '普通高中课程方案中%s部分是怎么规定的？' % topic
    if module:
        return '高中%s%s课程中，%s这部分的教学内容要求是什么？' % (subj, module.replace('课程', ''), topic)
    return '高中%s课程中，%s的教学内容要求是什么？' % (subj, topic)


def sample_stratified(chunks, total=40):
    """分层抽样: 主科优先, 每科至少 2 条"""
    random.seed(42)
    majors = ['语文', '数学', '英语', '物理', '化学', '生物', '思想政治', '历史', '地理', '信息技术']
    groups = {}
    for c in chunks:
        groups.setdefault(c['subject'], []).append(c)
    items = []
    for m in majors:
        pool = [c for c in groups.get(m, []) if len(c['content']) > 200]
        if pool:
            random.shuffle(pool)
            items.extend(pool[:3])
    # 补满
    rest = [c for c in chunks if c['subject'] not in majors and len(c['content']) > 200]
    random.shuffle(rest)
    for c in rest:
        if len(items) >= total:
            break
        items.append(c)
    if len(items) < total:
        extras = [c for c in chunks if len(c['content']) > 200 and c not in items]
        random.shuffle(extras)
        items.extend(extras[:total - len(items)])
    return items[:total]


def main():
    chunks = json.load(open(CHUNKS, encoding='utf-8'))
    items = sample_stratified(chunks, 40)
    print('评测查询:', len(items), '| 学科分布:', dict(json.Counter if False else __import__('collections').Counter(c['subject'] for c in items)))

    coll = get_collection()
    coll.load()

    mrr_raw, mrr_rr = [], []
    hit3_rr = 0
    for c in items:
        q = make_query(c)
        target = c['content'][:60]
        try:
            vec = embed(q)
            res = coll.search(data=[vec], anns_field='vector',
                              param={'metric_type': 'L2', 'params': {'nprobe': 16}},
                              limit=15, output_fields=['content'])
            hits = [str(h.entity.get('content', ''))[:60] for h in res[0]]
        except Exception as e:
            print('查询失败:', q[:30], str(e)[:60])
            mrr_raw.append(0.0)
            mrr_rr.append(0.0)
            continue
        # 环节 A: 粗排 MRR@15
        if target in hits:
            rank = hits.index(target) + 1
            mrr_raw.append(1.0 / rank)
        else:
            mrr_raw.append(0.0)
        # 环节 C: rerank 精排 top-3
        try:
            docs = [str(h.entity.get('content', '')) for h in res[0][:10]]
            idxs = rerank(q, docs, 3)
            rr_hits = [hits[i] for i in idxs if i < len(hits)]
            if target in rr_hits:
                rank = rr_hits.index(target) + 1
                mrr_rr.append(1.0 / rank)
                if rank == 1:
                    hit3_rr += 1
            else:
                mrr_rr.append(0.0)
        except Exception as e:
            print('rerank 失败:', str(e)[:60])
            mrr_rr.append(0.0)
        time.sleep(0.2)

    n = len(items)
    print('=== 新课标两环节评测 ===')
    print('MRR@15 纯向量粗排: %.4f' % (sum(mrr_raw) / n))
    print('MRR@3  +rerank精排: %.4f' % (sum(mrr_rr) / n))
    print('HitRate@3 精排: %.4f' % (hit3_rr / n))


if __name__ == '__main__':
    main()
