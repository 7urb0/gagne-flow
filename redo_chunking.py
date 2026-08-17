# -*- coding: utf-8 -*-
"""分块重做: 超长块二次切分加 100 字重叠(防语义截断), 重新生成 chunks 并重灌 Milvus"""
import os
import sys
import json
import time
import uuid
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pymilvus import connections, Collection

sys.path.insert(0, r'D:\project\agent\GagneFlow')
KEY = os.environ.get('DASHSCOPE_API_KEY', '')
SIZE = 2400      # 有效块长
OVERLAP = 100    # 重叠窗口

HS = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_chunks_clean.json'
YW = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_yiwu_chunks_clean.json'
HS_V2 = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_chunks_clean_v2.json'
YW_V2 = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_yiwu_chunks_clean_v2.json'


def split_with_overlap(text, size=SIZE, overlap=OVERLAP):
    """超长块按 size 切分, 相邻块共享 overlap 字(防边界语义截断); 短块原样返回"""
    if len(text) <= size:
        return [text]
    chunks = []
    start = 0
    while start < len(text):
        end = min(start + size, len(text))
        chunks.append(text[start:end])
        if end >= len(text):
            break
        start = end - overlap
    return chunks


def redo(src, dst):
    data = json.load(open(src, encoding='utf-8'))
    out = []
    stats = {'原片': len(data), '切后': 0, '重叠切分': 0}
    for c in data:
        subs = split_with_overlap(c['content'])
        if len(subs) > 1:
            stats['重叠切分'] += 1
        for sub in subs:
            out.append({**c, 'content': sub})
    stats['切后'] = len(out)
    json.dump(out, open(dst, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('%s: 原 %d 片 -> 重叠切分后 %d 片 (含 %d 片超长块)' % (
        os.path.basename(dst), stats['原片'], stats['切后'], stats['重叠切分']))
    return out


def embed(text, retries=3):
    body = json.dumps({'model': 'text-embedding-v4', 'input': {'texts': [text]}}).encode('utf-8')
    req = urllib.request.Request(
        'https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding',
        data=body, headers={'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json'})
    for a in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.loads(resp.read().decode('utf-8'))['output']['embeddings'][0]['embedding']
        except Exception:
            if a == retries - 1:
                raise
            time.sleep(2 * (a + 1))


def reload_milvus(all_chunks):
    connections.connect(alias='default', host='127.0.0.1', port='19530')
    coll = Collection('biz')
    coll.load()
    # 删全部课标分片
    r = coll.query(expr='metadata["_source"] == "curriculum_2022"', output_fields=['id'], limit=5000)
    print('删除旧课标分片:', len(r))
    if r:
        ids = [x['id'] for x in r]
        for i in range(0, len(ids), 500):
            coll.delete('id in [%s]' % ','.join('"%s"' % x for x in ids[i:i + 500]))
        coll.flush()
        coll.load()

    def work(c):
        vec = embed(c['content'])
        meta = {'_source': 'curriculum_2022', '_subject': c['subject'],
                '_stage': c.get('stage', '高中'), '_module': c.get('module', '课程内容'),
                '_topic': c['topic'][:40]}
        return (str(uuid.uuid4()), vec, c['content'], meta)

    rows = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        futs = [ex.submit(work, c) for c in all_chunks]
        for f in as_completed(futs):
            try:
                rows.append(f.result())
            except Exception as e:
                print('embed 失败:', str(e)[:50])
    print('embedding 成功:', len(rows))
    B = 100
    for i in range(0, len(rows), B):
        batch = rows[i:i + B]
        coll.insert([[r[0] for r in batch], [r[1] for r in batch],
                     [r[2] for r in batch], [r[3] for r in batch]])
    coll.flush()
    coll.load()
    r2 = coll.query(expr='id != ""', output_fields=['metadata'], limit=5000)
    from collections import Counter
    stages = Counter((x['metadata'] or {}).get('_stage', '?') for x in r2 if (x['metadata'] or {}).get('_source') == 'curriculum_2022')
    print('灌入完成: 课标 %d 条, 学段 %s, 总实体 %d' % (len(rows), dict(stages), len(r2)))


def main():
    hs_v2 = redo(HS, HS_V2)
    yw_v2 = redo(YW, YW_V2)
    # 校验: 相邻块共享 overlap
    for c in hs_v2 + yw_v2:
        assert len(c['content']) <= SIZE + OVERLAP, '块超长: %d' % len(c['content'])
    print('块长校验通过 (<= %d)' % (SIZE + OVERLAP))
    reload_milvus(hs_v2 + yw_v2)


if __name__ == '__main__':
    main()
