# -*- coding: utf-8 -*-
"""义务教育课标 OCR 文本解析: 按【内容要求】切块 + 回溯学段/领域"""
import re
import os
import json

OCR_DIR = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_ocr'
OUT = r'D:\project\agent\GagneFlow\lesson-plan-docs\primary_chunks.json'

SUBJECTS = {
    '语文.txt': '语文', '数学.txt': '数学', '英语.txt': '英语',
    '物理.txt': '物理', '化学.txt': '化学', '生物学.txt': '生物学',
    '道德与法治.txt': '道德与法治', '历史.txt': '历史', '地理.txt': '地理',
    '科学.txt': '科学', '信息科技.txt': '信息科技',
    '体育与健康.txt': '体育与健康', '艺术.txt': '艺术',
    '日语.txt': '日语', '俄语.txt': '俄语', '劳动.txt': '劳动',
}


def load_body(path, subject):
    raw = open(path, encoding='utf-8').read()
    body = re.sub(r'===PAGE \d+===', '', raw)
    body = re.sub(r'义务教育[^（]{1,12}课程标准（2022年版）\d*', '', body)
    body = re.sub(r'\d+前言', '', body)
    # 去 OCR 噪声: 连续空白压缩
    body = re.sub(r'\s+', '', body)
    return body


def parse_subject(path, subject):
    body = load_body(path, subject)
    anchors = [m.start() for m in re.finditer(r'【内容要求】', body)]
    if not anchors:
        # 无【内容要求】: 用 '内容要求' 独立标题
        anchors = [m.start() for m in re.finditer(r'内容要求', body)]
    if not anchors:
        return []
    # 学段标题模式
    chunks = []
    for n, a in enumerate(anchors):
        end = anchors[n + 1] if n + 1 < len(anchors) else len(body)
        # 若区间过长(>6000字)截断
        if end - a > 6000:
            end = a + 6000
        block = body[a:end]
        if len(block) < 100:
            continue
        # 回溯学段
        before = body[max(0, a - 300):a]
        m = re.findall(r'第[一二三四]学段（[^）]*）', before)
        stage = m[-1] if m else '义务教育'
        # 领域回溯
        m2 = re.findall(r'（[一二三四五六七八九十]+）[\u4e00-\u9fff]{2,8}', before)
        module = m2[-1] if m2 else '课程内容'
        chunks.append({'subject': subject, 'stage': '义务教育', 'module': module,
                       'topic': stage, 'content': block})
    return chunks


def split_long(chunks, limit=2500):
    """超长块按字符切分"""
    out = []
    for c in chunks:
        content = c['content']
        if len(content) <= limit:
            out.append(c)
            continue
        for k in range(0, len(content), limit):
            block = content[k:k + limit]
            if len(block) >= 100:
                cc = dict(c)
                cc['content'] = block
                out.append(cc)
    return out


def parse_chinese(path):
    """语文: 按学习任务群切分"""
    body = load_body(path, '语文')
    # 任务群标题: '1．基础型学习任务群语言文字积累与梳理'
    pat = re.compile(r'\d+．[^\d]{0,8}学习任务群[\u4e00-\u9fff]{4,12}')
    anchors = [m.start() for m in pat.finditer(body)]
    if not anchors:
        return []
    chunks = []
    for n, a in enumerate(anchors):
        end = anchors[n + 1] if n + 1 < len(anchors) else len(body)
        if end - a > 6000:
            end = a + 6000
        block = body[a:end]
        if len(block) < 100:
            continue
        title = pat.search(body[a:a + 40]).group(0)
        chunks.append({'subject': '语文', 'stage': '义务教育', 'module': '学习任务群',
                       'topic': title, 'content': block})
    return chunks


def main():
    all_chunks = []
    for fname, subj in SUBJECTS.items():
        p = os.path.join(OCR_DIR, fname)
        if not os.path.exists(p):
            print('缺失:', fname)
            continue
        if subj == '语文':
            chunks = parse_chinese(p)
        else:
            chunks = parse_subject(p, subj)
        chunks = split_long(chunks)
        print('%s: %d 片' % (subj, len(chunks)))
        all_chunks.extend(chunks)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(all_chunks, f, ensure_ascii=False, indent=1)
    print('总计:', len(all_chunks), '->', OUT)
    # 样例
    for c in all_chunks[:4]:
        print('---', '[' + c['subject'] + '|' + c['module'] + '|' + c['topic'] + ']', len(c['content']))


if __name__ == '__main__':
    main()
