# -*- coding: utf-8 -*-
"""
新课标 MRR 评测 v4: 三档查询改写对比（no / rule / llm）
- 数据集 A: 40 条独立查询（现有口径, 验证 LLM 开启不伤害独立查询）
- 数据集 B: 20 条上下文依赖查询（带 2 轮历史 + 指代词/省略, 体现 LLM 改写价值）
- 每档: 纯向量粗排 MRR@15 -> qwen3-rerank 精排 MRR@3 / HitRate@3
- LLM 改写复刻 QueryRewriter.java: qwen-turbo 温度 0.1 maxToken 200 + 公共子串>=2 校验
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

SHORT_LEN = 10
PROMPT = """你是一个查询改写助手。根据对话历史，将用户的查询改写为独立、完整的检索查询。

规则：
1. 将指代词（"它"、"这个"、"上次说的"、"那个方案"）替换为对话中的具体内容
2. 补充上下文中省略的主语、宾语、学科、年级等信息
3. 如果查询已经完整独立（无指代词、主语完整），必须原样返回，一个字都不要改
4. 输出只有改写后的查询文本，不要加引号、不要解释、不要标注
5. 改写结果必须保留原查询的核心词（学科、年级、知识点），只补充缺失的上下文"""


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


# ---------- 三档改写 ----------
def no_rewrite(q, history):
    return q


def rule_rewrite(q, history):
    """复刻 QueryRewriter 规则路径: 短查询拼最后一条 user 消息 + extractKeywords 关键词"""
    question = q.strip()
    if len(question) < SHORT_LEN and history:
        last_user = None
        for msg in reversed(history):
            if msg['role'] == 'user':
                last_user = msg['content']
                break
        if last_user and last_user.strip():
            question = last_user.strip() + ' ' + question
    # extractKeywords: 引号词 + 年级/课时/单元/章/课/节/学期/学段
    import re
    kws = []
    for m in re.finditer(r'[\u300c""]([^\u300d""]+)[\u300d""]', question):
        t = m.group(1).strip()
        if len(t) > 1:
            kws.append(t)
    for m in re.finditer(r'((\d+|[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]?))\s*(\u5e74\u7ea7|\u8bfe\u65f6|\u5355\u5143|\u7ae0|\u8bfe|\u8282|\u5b66\u671f|\u5b66\u6bb5)', question):
        kws.append(m.group())
    if kws:
        question = question + ' ' + ' '.join(kws)
    return question


def longest_common_substring(a, b):
    max_len = 0
    dp = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            if a[i - 1] == b[j - 1]:
                dp[i][j] = dp[i - 1][j - 1] + 1
                max_len = max(max_len, dp[i][j])
    return max_len


def valid_rewrite(original, rewritten):
    o = original.replace(' ', '')
    r = rewritten.replace(' ', '')
    if not o or not r:
        return False
    if r in o or o in r:
        return True
    return longest_common_substring(o, r) >= 2


def llm_rewrite(q, history):
    """复刻 QueryRewriter.rewriteWithLlm: qwen-turbo + 校验, 失败返回 None"""
    hist_text = []
    for msg in history[-6:]:
        role = '用户' if msg['role'] == 'user' else '助手'
        content = msg['content']
        if len(content) > 200:
            content = content[:200] + '...'
        hist_text.append('%s: %s' % (role, content))
    user_msg = '对话历史：\n' + '\n'.join(hist_text) + '\n用户查询：' + q
    body = json.dumps({
        'model': 'qwen-turbo',
        'input': {'messages': [
            {'role': 'system', 'content': PROMPT},
            {'role': 'user', 'content': user_msg},
        ]},
        'parameters': {'temperature': 0.1, 'max_tokens': 200},
    }).encode('utf-8')
    req = urllib.request.Request(
        'https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation',
        data=body, headers={'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode('utf-8'))
        rewritten = data['output']['text'].strip()
    except Exception as e:
        print('LLM 改写调用失败:', str(e)[:60])
        return None
    if not rewritten:
        return None
    if rewritten == q or valid_rewrite(q, rewritten):
        return rewritten
    print('LLM 改写校验失败, 降级:', q[:20], '->', rewritten[:30])
    return None


def rewrite_for(q, history, mode):
    if mode == 'no':
        return no_rewrite(q, history)
    if mode == 'rule':
        return rule_rewrite(q, history)
    r = llm_rewrite(q, history)
    if r is None:
        r = rule_rewrite(q, history)
    return r


# ---------- 数据集 ----------
def make_indep_query(c):
    """独立完整查询（数据集 A）"""
    subj = c['subject']
    topic = (c['topic'] or '').replace('：', '').strip()
    module = c['module'] if c['module'] != '课程内容' else ''
    if subj == '课程方案':
        return '普通高中课程方案中%s部分是怎么规定的？' % topic
    if module:
        return '高中%s%s课程中，%s这部分的教学内容要求是什么？' % (subj, module.replace('课程', ''), topic)
    return '高中%s课程中，%s的教学内容要求是什么？' % (subj, topic)


def make_ctx_item(c, template):
    """上下文依赖查询（数据集 B）: 2 轮历史 + 指代/省略新查询"""
    subj = c['subject']
    topic = (c['topic'] or '').replace('：', '').strip()
    module = c['module'] if c['module'] != '课程内容' else ''
    anchor = '%s%s的%s' % (subj, module.replace('课程', '') if module else '', topic[:18])
    history = [
        {'role': 'user', 'content': '高中%s这门课里 %s 的内容怎么教比较合适？' % (subj, topic[:18])},
        {'role': 'assistant', 'content': '建议从 %s 的核心概念入手，先建立整体框架，再结合课标要求逐条展开教学。' % topic[:12]},
    ]
    if template == '指代':
        q = '上次说的那个导入思路，还有其他可用的例子吗？'
    elif template == '省略主语':
        q = '有哪些重点内容？'
    elif template == '短查询':
        q = '还有例子吗？'
    elif template == '延续追问':
        q = '那它适合什么学段的学生？'
    return {'query': q, 'history': history, 'target': c['content'][:60], 'anchor': anchor}


def main():
    chunks = json.load(open(CHUNKS, encoding='utf-8'))
    coll = get_collection()
    coll.load()

    # 数据集 A: 40 条独立查询（分层）
    random.seed(42)
    majors = ['语文', '数学', '英语', '物理', '化学', '生物', '思想政治', '历史', '地理', '信息技术']
    groups = {}
    for c in chunks:
        groups.setdefault(c['subject'], []).append(c)
    items_a = []
    for m in majors:
        pool = [c for c in groups.get(m, []) if len(c['content']) > 200]
        if pool:
            random.shuffle(pool)
            items_a.extend(pool[:3])
    rest = [c for c in chunks if c['subject'] not in majors and len(c['content']) > 200]
    random.shuffle(rest)
    for c in rest:
        if len(items_a) >= 40:
            break
        items_a.append(c)
    if len(items_a) < 40:
        extras = [c for c in chunks if len(c['content']) > 200 and c not in items_a]
        random.shuffle(extras)
        items_a.extend(extras[:40 - len(items_a)])

    # 数据集 B: 20 条上下文依赖查询（主科池, 4 种模板各 5 条）
    pool_b = [c for c in chunks if c['subject'] in majors and len(c['content']) > 200]
    random.seed(7)
    random.shuffle(pool_b)
    templates = ['指代'] * 5 + ['省略主语'] * 5 + ['短查询'] * 5 + ['延续追问'] * 5
    items_b = [make_ctx_item(pool_b[i], templates[i]) for i in range(20)]

    # 通用评测函数
    def eval_items(items, qfn, label):
        modes = ['no', 'rule', 'llm']
        results = {m: {'mrr_raw': [], 'mrr_rr': [], 'hit3': 0} for m in modes}
        n = len(items)
        for item in items:
            for mode in modes:
                q = qfn(item) if mode == 'no' else rewrite_for(qfn(item), item.get('history') or [], mode)
                target = item.get('target') or item['content'][:60]
                try:
                    vec = embed(q)
                    res = coll.search(data=[vec], anns_field='vector',
                                      param={'metric_type': 'L2', 'params': {'nprobe': 16}},
                                      limit=15, output_fields=['content'])
                    hits = [str(h.entity.get('content', ''))[:60] for h in res[0]]
                except Exception as e:
                    print('检索失败:', q[:20], str(e)[:50])
                    results[mode]['mrr_raw'].append(0.0)
                    results[mode]['mrr_rr'].append(0.0)
                    continue
                if target in hits:
                    results[mode]['mrr_raw'].append(1.0 / (hits.index(target) + 1))
                else:
                    results[mode]['mrr_raw'].append(0.0)
                try:
                    docs = [str(h.entity.get('content', '')) for h in res[0][:10]]
                    idxs = rerank(q, docs, 3)
                    rr = [hits[i] for i in idxs if i < len(hits)]
                    if target in rr:
                        rk = rr.index(target) + 1
                        results[mode]['mrr_rr'].append(1.0 / rk)
                        if rk == 1:
                            results[mode]['hit3'] += 1
                    else:
                        results[mode]['mrr_rr'].append(0.0)
                except Exception:
                    results[mode]['mrr_rr'].append(0.0)
                time.sleep(0.15)
        print()
        print('=== %s (%d 条) ===' % (label, n))
        for m in modes:
            print('%-5s 粗排MRR@15: %.4f | 精排MRR@3: %.4f | HitRate@3: %.1f%%' % (
                m.upper(), sum(results[m]['mrr_raw']) / n,
                sum(results[m]['mrr_rr']) / n, results[m]['hit3'] / n * 100))

    eval_items(items_a, lambda c: make_indep_query(c), '数据集A 独立查询')
    eval_items(items_b, lambda it: it['query'], '数据集B 上下文依赖查询')

    print()
    print('说明: 数据集A 验证 LLM 开启不伤害独立查询; 数据集B 体现 LLM 改写对指代/省略查询的价值')


if __name__ == '__main__':
    main()
