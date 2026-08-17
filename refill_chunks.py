# -*- coding: utf-8 -*-
"""补灌 embed 失败的 115 块: 串行 + 重试 5 次"""
import os
import sys
import json
import time
import uuid
import urllib.request

sys.path.insert(0, r'D:\project\agent\GagneFlow')
KEY = os.environ.get('DASHSCOPE_API_KEY', '')
from pymilvus import connections, Collection


def embed(text, retries=5):
    body = json.dumps({'model': 'text-embedding-v4', 'input': {'texts': [text]}}).encode('utf-8')
    req = urllib.request.Request(
        'https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding',
        data=body, headers={'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json'})
    for a in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.loads(resp.read().decode('utf-8'))['output']['embeddings'][0]['embedding']
        except Exception as e:
            if a == retries - 1:
                raise
            time.sleep(3 * (a + 1))


def main():
    connections.connect(alias='default', host='127.0.0.1', port='19530')
    coll = Collection('biz')
    coll.load()
    r = coll.query(expr='metadata["_source"] == "curriculum_2022"', output_fields=['content'], limit=5000)
    in_db = set((x['content'] or '')[:100] for x in r)
    hs = json.load(open(r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_chunks_clean_v2.json', encoding='utf-8'))
    yw = json.load(open(r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_yiwu_chunks_clean_v2.json', encoding='utf-8'))
    missing = [c for c in hs + yw if c['content'][:100] not in in_db]
    print('待补灌:', len(missing))

    ok, fail = 0, []
    for c in missing:
        try:
            vec = embed(c['content'])
            meta = {'_source': 'curriculum_2022', '_subject': c['subject'],
                    '_stage': c.get('stage', '高中'), '_module': c.get('module', '课程内容'),
                    '_topic': c['topic'][:40]}
            coll.insert([[str(uuid.uuid4())], [vec], [c['content']], [meta]])
            ok += 1
        except Exception as e:
            fail.append((c['subject'], len(c['content']), str(e)[:60]))
            print('失败:', c['subject'], len(c['content']), str(e)[:50])
        time.sleep(0.3)
    coll.flush()
    coll.load()
    r2 = coll.query(expr='metadata["_source"] == "curriculum_2022"', output_fields=['content'], limit=5000)
    print('补灌完成: 成功 %d, 失败 %d | 课标总数 %d' % (ok, len(fail), len(r2)))
    for f in fail[:5]:
        print('  残留失败:', f)


if __name__ == '__main__':
    main()
