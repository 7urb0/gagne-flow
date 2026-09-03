# -*- coding: utf-8 -*-
"""课标解析器 v10: 20 科高中课标统一解析（req块+模块兜底混合）+ 课程方案"""
import fitz
import re
import os
import json

CUR = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum'
OUT = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_chunks.json'

SUBJECTS = {
    '高中语文课标2020.pdf': '语文',
    '高中数学课标2020.pdf': '数学',
    '高中英语课标2020.pdf': '英语',
    '高中物理课标2020.pdf': '物理',
    '高中化学课标2020.pdf': '化学',
    '高中生物课标2020.pdf': '生物',
    '高中政治课标2020.pdf': '思想政治',
    '高中历史课标2020.pdf': '历史',
    '高中地理课标2020.pdf': '地理',
    '高中信息技术课标2020.pdf': '信息技术',
    '高中通用技术课标2020.pdf': '通用技术',
    '高中艺术课标2020.pdf': '艺术',
    '高中音乐课标2020.pdf': '音乐',
    '高中美术课标2020.pdf': '美术',
    '高中体育课标2020.pdf': '体育与健康',
    '高中日语课标2020.pdf': '日语',
    '高中俄语课标2020.pdf': '俄语',
    '高中德语课标2020.pdf': '德语',
    '高中法语课标2020.pdf': '法语',
    '高中西班牙语课标2020.pdf': '西班牙语',
}
HEADER_PAT = re.compile(r'普通高中.{1,8}课程标准（2017年版2020年修订）')
CAT_STR = ('必修课程', '选择性必修课程', '选修课程')


def clean_text(full):
    lines = []
    for l in full.split('\n'):
        s = l.strip()
        if not s:
            continue
        if HEADER_PAT.search(s):
            continue
        if chr(0x2502) in s:
            continue
        if re.fullmatch(r'\d{1,4}', s):
            continue
        if '.....' in s or '……' in s:
            continue
        lines.append(s)
    return lines


def detect_module(lines, idx):
    for i in range(idx, max(0, idx - 400), -1):
        s = lines[i]
        if len(s) < 20 and not re.search(r'[，。；：]', s):
            for k in CAT_STR:
                if k in s:
                    return k
    return '课程内容'


def extract_req_chunks(lines, req_idxs, start, end, subject):
    chunks = []
    for n, ridx in enumerate(req_idxs):
        e = req_idxs[n + 1] if n + 1 < len(req_idxs) else end
        topic = lines[ridx - 1] if ridx > start and len(lines[ridx - 1]) < 40 else '课程内容'
        module = detect_module(lines, ridx)
        content = '\n'.join(lines[ridx:e]).strip()
        if len(content) > 8000:
            sub_lines = lines[ridx:e]
            for k in range(0, len(sub_lines), 150):
                block = '\n'.join(sub_lines[k:k + 150]).strip()
                if len(block) >= 100:
                    chunks.append({'subject': subject, 'stage': '高中', 'module': module,
                                   'topic': topic, 'content': block})
            continue
        if 100 <= len(content) <= 8000:
            chunks.append({'subject': subject, 'stage': '高中', 'module': module,
                           'topic': topic, 'content': content})
    return chunks


def extract_module_chunks(lines, start, end, subject):
    anchor_pat = re.compile(r'^(模块|大概念|主题|学习任务群)\s*\d')
    anchors = [i for i in range(start, end) if anchor_pat.match(lines[i])]
    if not anchors:
        return []
    chunks = []
    for n, ai in enumerate(anchors):
        ae = anchors[n + 1] if n + 1 < len(anchors) else end
        module = detect_module(lines, ai)
        content = '\n'.join(lines[ai:ae]).strip()
        if len(content) < 100:
            continue
        if len(content) > 8000:
            sub_reqs = [i for i in range(ai, ae)
                        if lines[i] == '【内容要求】' or lines[i].startswith('【内容要求】')]
            if sub_reqs:
                for m, ridx in enumerate(sub_reqs):
                    re2 = sub_reqs[m + 1] if m + 1 < len(sub_reqs) else ae
                    sub = '\n'.join(lines[ridx:re2]).strip()
                    if len(sub) > 8000:
                        sub_lines = lines[ridx:re2]
                        for k in range(0, len(sub_lines), 150):
                            block = '\n'.join(sub_lines[k:k + 150]).strip()
                            if len(block) >= 100:
                                chunks.append({'subject': subject, 'stage': '高中', 'module': module,
                                               'topic': lines[ai], 'content': block})
                        continue
                    if 100 <= len(sub) <= 8000:
                        chunks.append({'subject': subject, 'stage': '高中', 'module': module,
                                       'topic': lines[ai], 'content': sub})
                continue
            chunks.append({'subject': subject, 'stage': '高中', 'module': module,
                           'topic': lines[ai], 'content': content[:8000]})
            continue
        chunks.append({'subject': subject, 'stage': '高中', 'module': module,
                       'topic': lines[ai], 'content': content})
    return chunks


def dedup(chunks):
    seen = set()
    out = []
    for c in chunks:
        key = (c['subject'], c['content'][:60])
        if key in seen:
            continue
        seen.add(key)
        out.append(c)
    return out


def parse_pdf(pdf, subject):
    doc = fitz.open(pdf)
    full = ''.join(doc[i].get_text() for i in range(doc.page_count))
    doc.close()
    lines = clean_text(full)
    req_idxs = [i for i, l in enumerate(lines)
                if l == '【内容要求】' or l.startswith('【内容要求】') or l == '内容要求']
    if not req_idxs:
        # 无内容要求标题(如语文学习任务群): 定位课程内容章节, 走模块锚点+兜底
        start = 0
        for i, l in enumerate(lines):
            if len(l) < 20 and '课程内容' in l:
                start = i
                break
        chunks = extract_module_chunks(lines, start, len(lines), subject)
        if not chunks:
            seg = lines[start:len(lines)]
            cur, cur_len = [], 0
            for l in seg:
                cur.append(l)
                cur_len += len(l)
                if cur_len >= 2000:
                    block = '\n'.join(cur).strip()
                    if len(block) >= 100:
                        chunks.append({'subject': subject, 'stage': '高中', 'module': '课程内容',
                                       'topic': lines[start], 'content': block})
                    cur, cur_len = [], 0
            if cur:
                block = '\n'.join(cur).strip()
                if len(block) >= 100:
                    chunks.append({'subject': subject, 'stage': '高中', 'module': '课程内容',
                                   'topic': lines[start], 'content': block})
        return chunks
    start = 0
    for i, l in enumerate(lines[:req_idxs[0] + 1]):
        if ('（一）必修课程' in l or l.startswith('四、课程内容') or l.startswith('四 课程内容')) and '／' not in l and '.....' not in l:
            start = i
            break
    end = len(lines)
    for i in range(req_idxs[-1], min(len(lines), req_idxs[-1] + 800)):
        s = lines[i]
        if len(s) < 20 and re.match(r'^五、学业质量|^五 学业质量|学业质量', s):
            end = i
            break
    chunks = extract_req_chunks(lines, req_idxs, start, end, subject)
    chunks += extract_module_chunks(lines, start, end, subject)
    chunks = dedup(chunks)
    if not chunks:
        # 兜底: 课程内容章节按 2000 字符切块
        seg = lines[start:end]
        cur, cur_len = [], 0
        for l in seg:
            cur.append(l)
            cur_len += len(l)
            if cur_len >= 2000:
                block = '\n'.join(cur).strip()
                if len(block) >= 100:
                    chunks.append({'subject': subject, 'stage': '高中', 'module': '课程内容',
                                   'topic': lines[start], 'content': block})
                cur, cur_len = [], 0
        if cur:
            block = '\n'.join(cur).strip()
            if len(block) >= 100:
                chunks.append({'subject': subject, 'stage': '高中', 'module': '课程内容',
                               'topic': lines[start], 'content': block})
    return chunks


def parse_plan(pdf):
    """课程方案: 按章节切分"""
    doc = fitz.open(pdf)
    full = ''.join(doc[i].get_text() for i in range(doc.page_count))
    doc.close()
    lines = clean_text(full)
    # 按 '一、' '二、' 等章节切
    anchors = [i for i, l in enumerate(lines) if re.match(r'^[一二三四五六七八九十]、', l)]
    chunks = []
    for n, ai in enumerate(anchors):
        ae = anchors[n + 1] if n + 1 < len(anchors) else len(lines)
        content = '\n'.join(lines[ai:ae]).strip()
        if 100 <= len(content) <= 8000:
            chunks.append({'subject': '课程方案', 'stage': '高中', 'module': '课程方案',
                           'topic': lines[ai], 'content': content})
    return chunks


def main():
    all_chunks = []
    for fname, subj in SUBJECTS.items():
        p = os.path.join(CUR, fname)
        if not os.path.exists(p):
            print('缺失:', fname)
            continue
        chunks = parse_pdf(p, subj)
        print('%s: %d 片' % (subj, len(chunks)))
        all_chunks.extend(chunks)
    # 课程方案
    pp = os.path.join(CUR, '高中课程方案2020.pdf')
    if os.path.exists(pp):
        plan = parse_plan(pp)
        print('课程方案: %d 片' % len(plan))
        all_chunks.extend(plan)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(all_chunks, f, ensure_ascii=False, indent=1)
    print('总计:', len(all_chunks), '->', OUT)


if __name__ == '__main__':
    main()
