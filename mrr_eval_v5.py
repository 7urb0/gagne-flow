# -*- coding: utf-8 -*-
"""
新课标 MRR 评测 v5: 义务教育入库后全量知识库(1261)主口径重跑
- 评测集 A: 高中主科 40 条 (语数英物化生政史地信技各4, 目标=高中分片) -> 对比旧 657 库的 0.59/0.63/70%
- 评测集 B: 义务教育主科 40 条 (数学/语文/英语/物理/化学/生物/道法/历史/地理/科学各4, 目标=义务教育分片) -> 新指标
- 每集: 粗排 MRR@15 -> qwen3-rerank 精排 MRR@3 / HitRate@3
"""
import os
import sys
import json
import random
import time
import urllib.request

sys.path.insert(0, r'D:\project\agent\GagneFlow')
from mrr_eval import get_collection

KEY = os.environ.get('DASHSCOPE_API_KEY', '')
HS_CHUNKS = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_chunks.json'
YW_CHUNKS = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_yiwu_chunks.json'


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


def hs_query(c):
    topic = (c['topic'] or '').replace('：', '').strip()
    module = c['module'] if c['module'] != '课程内容' else ''
    if module:
        return '高中%s%s课程中，%s这部分的教学内容要求是什么？' % (c['subject'], module.replace('课程', ''), topic)
    return '高中%s课程中，%s的教学内容要求是什么？' % (c['subject'], topic)


def yw_query(c):
    # C1 修复: 取 target 之后的第一个完整句(而非碎片), 提高查询语义质量
    seg = c['content'][60:240]
    for sep in ['。', '；', '；']:
        p = seg.find(sep)
        if 10 < p < 90:
            seg = seg[:p]
            break
    core = seg.replace('，', '').replace('：', '').replace('、', '').replace(' ', '')
    return '义务教育%s课程中，%s这部分的教学内容要求是什么？' % (c['subject'], core[:20])


def sample(items, majors, per, total):
    random.seed(42)
    groups = {}
    for c in items:
        groups.setdefault(c['subject'], []).append(c)
    out = []
    for m in majors:
        pool = [c for c in groups.get(m, []) if len(c['content']) > 150]
        if pool:
            random.shuffle(pool)
            out.extend(pool[:per])
    return out[:total]


def evaluate(items, qfn, label):
    coll = get_collection()
    coll.load()
    mrr_raw, mrr_rr = [], []
    hit3 = 0
    for c in items:
        q = qfn(c)
        target = c['content'][:60]
        try:
            vec = embed(q)
            res = coll.search(data=[vec], anns_field='vector',
                              param={'metric_type': 'L2', 'params': {'nprobe': 16}},
                              limit=15, output_fields=['content'])
            hits = [str(h.entity.get('content', ''))[:60] for h in res[0]]
        except Exception as e:
            print('查询失败:', q[:25], str(e)[:50])
            mrr_raw.append(0.0)
            mrr_rr.append(0.0)
            continue
        if target in hits:
            mrr_raw.append(1.0 / (hits.index(target) + 1))
        else:
            mrr_raw.append(0.0)
        try:
            docs = [str(h.entity.get('content', '')) for h in res[0][:10]]
            idxs = rerank(q, docs, 3)
            rr = [hits[i] for i in idxs if i < len(hits)]
            if target in rr:
                rk = rr.index(target) + 1
                mrr_rr.append(1.0 / rk)
                if rk == 1:
                    hit3 += 1
            else:
                mrr_rr.append(0.0)
        except Exception as e:
            print('rerank 失败:', str(e)[:50])
            mrr_rr.append(0.0)
        time.sleep(0.15)
    n = len(items)
    print('=== %s (%d 条, 全量 1261 库) ===' % (label, n))
    print('粗排 MRR@15: %.4f | 精排 MRR@3: %.4f | HitRate@3: %.1f%%' % (
        sum(mrr_raw) / n, sum(mrr_rr) / n, hit3 / n * 100))


def main():
    hs = json.load(open(HS_CHUNKS, encoding='utf-8'))
    yw = json.load(open(YW_CHUNKS, encoding='utf-8'))
    hs_majors = ['语文', '数学', '英语', '物理', '化学', '生物', '思想政治', '历史', '地理', '信息技术']
    yw_majors = ['数学', '语文', '英语', '物理', '化学', '生物学', '道德与法治', '历史', '地理', '科学']
    # 高中主科 40 条
    hs_items = sample(hs, hs_majors, 4, 40)
    print('高中评测查询:', len(hs_items), '| 学科:', dict(__import__('collections').Counter(c['subject'] for c in hs_items)))
    evaluate(hs_items, hs_query, '高中主科 40 条(对比旧库 0.59/0.63/70%)')
    # 义务教育主科 40 条
    yw_items = sample(yw, yw_majors, 4, 40)
    print()
    print('义务教育评测查询:', len(yw_items), '| 学科:', dict(__import__('collections').Counter(c['subject'] for c in yw_items)))
    evaluate(yw_items, yw_query, '义务教育主科 40 条(新增口径)')


if __name__ == '__main__':
    main()
