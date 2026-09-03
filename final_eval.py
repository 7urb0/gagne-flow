# -*- coding: utf-8 -*-
"""最终评测: 5 个 seed x 每学段 60 条(共 120/轮, 600 条), 抽样敏感性 + 最终口径定稿"""
import os
import sys
import json
import time
import random

sys.path.insert(0, r'D:\project\agent\GagneFlow')
import mrr_eval_v5 as v5

SEEDS = [42, 7, 99, 123, 2024]
PER_SUBJECT = 6  # 每科 6 条 x 10 科 = 60 条/学段
OUT_JSON = r'D:\project\agent\GagneFlow\lesson-plan-docs\final_eval_results.json'


def sample_seeded(items, majors, per, total, seed):
    random.seed(seed)
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


def one_round(hs_items, yw_items):
    from mrr_eval import get_collection
    coll = get_collection()
    coll.load()

    def run(items, qfn):
        mrr_raw, mrr_rr, hit1, hit3, hit5 = [], [], 0, 0, 0
        for c in items:
            q = qfn(c)
            target = c['content'][:60]
            try:
                vec = v5.embed(q)
                res = coll.search(data=[vec], anns_field='vector',
                                  param={'metric_type': 'L2', 'params': {'nprobe': 16}},
                                  limit=30, output_fields=['content'])
                hits = [str(h.entity.get('content', ''))[:60] for h in res[0]]
            except Exception:
                mrr_raw.append(0.0)
                mrr_rr.append(0.0)
                continue
            if target in hits:
                rank = hits.index(target) + 1
                mrr_raw.append(1.0 / rank)
                if rank <= 1:
                    hit1 += 1
                if rank <= 3:
                    hit3 += 1
                if rank <= 5:
                    hit5 += 1
            else:
                mrr_raw.append(0.0)
            try:
                docs = [str(h.entity.get('content', '')) for h in res[0][:20]]
                idxs = v5.rerank(q, docs, 5)
                rr = [hits[i] for i in idxs if i < len(hits)]
                if target in rr:
                    rk = rr.index(target) + 1
                    mrr_rr.append(1.0 / rk)
                    if rk <= 1:
                        hit1 += 0  # hit1 已按粗排计, 精排另算
                    if rk <= 3:
                        hit3 += 0
                else:
                    mrr_rr.append(0.0)
            except Exception:
                mrr_rr.append(0.0)
            time.sleep(0.1)
        n = len(items)
        return {
            'mrr_raw': round(sum(mrr_raw) / n, 4),
            'mrr_rr': round(sum(mrr_rr) / n, 4),
            'hit1': round(hit1 / n, 4),
            'hit3': round(hit3 / n, 4),
            'hit5': round(hit5 / n, 4),
        }

    return {'hs': run(hs_items, v5.hs_query), 'yw': run(yw_items, v5.yw_query)}


def main():
    hs = json.load(open(v5.HS_CHUNKS, encoding='utf-8'))
    yw = json.load(open(r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_yiwu_chunks_clean_v2.json', encoding='utf-8'))
    hs_majors = ['语文', '数学', '英语', '物理', '化学', '生物', '思想政治', '历史', '地理', '信息技术']
    yw_majors = ['数学', '语文', '英语', '物理', '化学', '生物学', '道德与法治', '历史', '地理', '科学']

    print('===== 最终评测: %d 个 seed x 120 条(%d 条查询) =====' % (len(SEEDS), len(SEEDS) * 120))
    rows = []
    for seed in SEEDS:
        hs_items = sample_seeded(hs, hs_majors, PER_SUBJECT, 60, seed)
        yw_items = sample_seeded(yw, yw_majors, PER_SUBJECT, 60, seed)
        t0 = time.time()
        r = one_round(hs_items, yw_items)
        rows.append({'seed': seed, **r})
        hs_r, yw_r = r['hs'], r['yw']
        print('seed=%d (%.0fs): 高中 粗%.3f/精%.3f/Hit1 %.0f%%/Hit3 %.0f%%/Hit5 %.0f%% | 义务 粗%.3f/精%.3f/Hit3 %.0f%%' % (
            seed, time.time() - t0,
            hs_r['mrr_raw'], hs_r['mrr_rr'], hs_r['hit1'] * 100, hs_r['hit3'] * 100, hs_r['hit5'] * 100,
            yw_r['mrr_raw'], yw_r['mrr_rr'], yw_r['hit3'] * 100))

    # 汇总
    print()
    print('===== 最终口径 (5 seed 汇总) =====')
    summary = {}
    for scope in ['hs', 'yw']:
        label = '高中' if scope == 'hs' else '义务教育'
        summary[scope] = {}
        for k in ['mrr_raw', 'mrr_rr', 'hit1', 'hit3', 'hit5']:
            vals = [r[scope][k] for r in rows]
            mean = sum(vals) / len(vals)
            spread = max(vals) - min(vals)
            summary[scope][k] = {'mean': round(mean, 4), 'min': min(vals), 'max': max(vals), 'spread': round(spread, 4)}
            print('%s %s: 均值 %.4f, 区间 [%.4f, %.4f], 极差 %.4f' % (label, k, mean, min(vals), max(vals), spread))
    # 综合(两学段平均)
    print()
    print('--- 综合(高中+义务教育均值) ---')
    comp = {}
    for k in ['mrr_raw', 'mrr_rr', 'hit1', 'hit3', 'hit5']:
        vals = [(r['hs'][k] + r['yw'][k]) / 2 for r in rows]
        mean = sum(vals) / len(vals)
        comp[k] = {'mean': round(mean, 4), 'min': round(min(vals), 4), 'max': round(max(vals), 4), 'spread': round(max(vals) - min(vals), 4)}
        print('综合 %s: 均值 %.4f, 区间 [%.4f, %.4f], 极差 %.4f' % (k, mean, min(vals), max(vals), spread))

    json.dump({'seeds': SEEDS, 'rows': rows, 'summary': summary, 'composite': comp},
              open(OUT_JSON, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print()
    print('结果已存:', OUT_JSON)


if __name__ == '__main__':
    main()
