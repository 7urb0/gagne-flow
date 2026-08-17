# -*- coding: utf-8 -*-
"""义务教育课标 OCR 解析分片 v2: 按页构建, 页内锚点(内容要求/学业要求/学段/主题)切块"""
import os
import re
import json

OCR_DIR = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_ocr'
OUT = r'D:\project\agent\GagneFlow\lesson-plan-docs\curriculum_yiwu_chunks.json'

SUBJECTS = ['语文', '数学', '英语', '物理', '化学', '生物学', '道德与法治', '历史', '地理',
            '科学', '信息科技', '体育与健康', '艺术', '日语', '俄语', '劳动']

HEADER_PAT = re.compile(r'义务教育(语文|数学|英语|物理|化学|生物学|道德与法治|历史|地理|科学|信息科技|体育与健康|艺术|日语|俄语|劳动)课程标准（2022年版）')
NOISE = ['中华人民共和国教育部制定', '北京师范大学出版集团', '北京师范大学出版社', '人民教育出版社', '目凵录']
SECTION_START = re.compile(r'^[一二三四五六七八九十]+、')


def clean_page_text(text):
    s = HEADER_PAT.sub('', text)
    for n in NOISE:
        s = s.replace(n, '')
    s = re.sub(r'^\s*[-•·]\s*', '', s)
    s = re.sub(r'\s+', '', s)
    return s.strip()


def load_pages(name):
    """返回 [(page_no, cleaned_text)]"""
    txt = open(os.path.join(OCR_DIR, name + '.txt'), encoding='utf-8').read()
    pages = re.findall(r'===PAGE (\d+)===\n(.*?)(?=\n===PAGE |\Z)', txt, re.S)
    return [(int(p), clean_page_text(b)) for p, b in pages if len(clean_page_text(b)) > 80]


def find_anchor_pages(pages, patterns):
    """返回含任一锚点模式的页索引列表"""
    return [i for i, (_, t) in enumerate(pages) if any(p in t for p in patterns)]


def detect_topic(pages, idx):
    """回溯最近的主题/学段/章节标题"""
    title_pats = [r'（[一二三四五六七八九十]+）', r'第[一二三四五六七八九十]+学段', r'学习任务群',
                  r'主题\s*[一二三四五六七八九十\d]', r'^[一二三四五六七八九十]+、']
    for j in range(idx, -1, -1):
        t = pages[j][1]
        for pat in title_pats:
            m = re.search(pat, t[:80])
            if m:
                return m.group(0)[:30]
    return '课程内容'


def main():
    all_chunks = []
    for subj in SUBJECTS:
        pages = load_pages(subj)
        if not pages:
            print('%s: 无有效页' % subj)
            continue
        chunks = []
        # 主锚点: 内容要求(多数理科, 需 >=3 个才可靠; 少则走定制锚点)
        req_idxs = find_anchor_pages(pages, ['内容要求'])
        use_req = len(req_idxs) >= 3
        if use_req:
            bounds = req_idxs + [len(pages)]
            for n, r in enumerate(req_idxs):
                topic = detect_topic(pages, r)
                # 块 = 内容要求页 到 下一个内容要求页或学业要求页
                e = bounds[n + 1]
                for j in range(r + 1, e):
                    if '学业要求' in pages[j][1] or '教学提示' in pages[j][1]:
                        e = j
                        break
                content = ''.join(t for _, t in pages[r:e]).strip()
                if len(content) >= 100:
                    chunks.append((topic, content))
            # 学业要求/教学提示块(内容要求之后的部分)
            req_set = set(req_idxs)
            xq = find_anchor_pages(pages, ['学业要求'])
            for x in xq:
                if x in req_set:
                    continue
                topic = detect_topic(pages, x)
                content = ''.join(t for i, (_, t) in enumerate(pages) if i >= x and (x + 1 >= len(pages) or i < min([b for b in bounds if b > x] + [len(pages)])))
                if len(content) >= 100:
                    chunks.append((topic, content))
        else:
            # 无内容要求: 按定制锚点(学科结构)切
            custom = {
                '语文': ['语言文字积累与梳理', '实用性阅读与交流', '文学阅读与创意表达',
                         '思辨性阅读与表达', '整本书阅读', '跨学科学习'],
                '英语': ['人与自我', '人与社会', '人与自然'],
            }
            anchor_kws = custom.get(subj, [])
            anchors = []
            anchor_titles = {}
            for i, (_, t) in enumerate(pages):
                hit = None
                for kw in anchor_kws:
                    if kw in t[:80]:
                        hit = kw
                        break
                if hit:
                    anchors.append(i)
                    anchor_titles[i] = hit
                    continue
                if re.search(r'第[一二三四五六七八九十]+学段|主题\s*[一二三四五六七八九十\d]|（[一二三四五六七八九十]+）', t[:60]):
                    anchors.append(i)
            if not anchors:
                anchors = list(range(0, len(pages), 2))
            bounds = anchors + [len(pages)]
            for n, a in enumerate(anchors):
                ae = bounds[n + 1]
                content = ''.join(t for _, t in pages[a:ae]).strip()
                if len(content) >= 100:
                    chunks.append((anchor_titles.get(a) or detect_topic(pages, a), content))
        # 去重 + 超长二次切
        seen = set()
        final = []
        for topic, content in chunks:
            # 超 7000 字符切块
            if len(content) > 7000:
                for k in range(0, len(content), 3500):
                    sub = content[k:k + 3500]
                    key = sub[:50]
                    if key in seen:
                        continue
                    seen.add(key)
                    final.append({'subject': subj, 'stage': '义务教育', 'module': '课程内容',
                                  'topic': topic[:40], 'content': sub})
                continue
            key = content[:50]
            if key in seen:
                continue
            seen.add(key)
            final.append({'subject': subj, 'stage': '义务教育', 'module': '课程内容',
                          'topic': topic[:40], 'content': content})
        all_chunks.extend(final)
        print('%s: %d 片 (有效页 %d)' % (subj, len(final), len(pages)))

    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(all_chunks, f, ensure_ascii=False, indent=1)
    print()
    print('总计:', len(all_chunks), '片')
    bad = [c for c in all_chunks if len(c['content']) < 80 or len(c['content']) > 8000]
    print('异常片(<80或>8000):', len(bad))
    for c in all_chunks[:3]:
        print('样例:', c['subject'], '|', c['topic'], '|', len(c['content']), '字')


if __name__ == '__main__':
    main()
