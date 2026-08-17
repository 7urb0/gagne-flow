# -*- coding: utf-8 -*-
"""从 md 产物恢复 OCR 文本: 扫描项目根 + ocr_pages 两个目录, 按 学科_页码 重组 txt"""
import os
import re

ROOT = r'D:\project\agent\GagneFlow'
OCR_DIR = os.path.join(ROOT, 'ocr_pages')
OUT_DIR = os.path.join(ROOT, 'lesson-plan-docs', 'curriculum_ocr')

PDFS = {'语文': 109, '数学': 189, '英语': 201, '物理': 71, '化学': 87, '生物学': 76,
        '道德与法治': 73, '历史': 86, '地理': 65, '科学': 193, '信息科技': 74,
        '体育与健康': 148, '艺术': 131, '日语': 147, '俄语': 111, '劳动': 68}

PAT = re.compile(r'(.+?)_p(\d{3})_\d{6}_\d{6}\.md$')


def extract_body(md_path):
    raw = open(md_path, encoding='utf-8').read()
    parts = raw.split('---')
    body = parts[-1].strip() if len(parts) >= 2 else raw.strip()
    body = re.sub(r'^■+', '', body)
    return body


def collect():
    pages = {}  # subj -> {page: md_path}
    for base in (ROOT, OCR_DIR):
        for f in os.listdir(base):
            m = PAT.match(f)
            if not m:
                continue
            subj, pg = m.group(1), int(m.group(2))
            pages.setdefault(subj, {})[pg] = os.path.join(base, f)
    return pages


def main():
    pages = collect()
    missing_all = {}
    for name, total in PDFS.items():
        pgmap = pages.get(name, {})
        missing = sorted(set(range(1, total + 1)) - set(pgmap))
        if missing:
            missing_all[name] = missing
            print('%s: 缺失 %d 页 %s' % (name, len(missing), missing[:10]))
            continue
        lines = ['===PAGE %d===\n%s' % (pg, extract_body(pgmap[pg])) for pg in sorted(pgmap)]
        out = os.path.join(OUT_DIR, name + '.txt')
        with open(out, 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines))
        chars = sum(len(l) for l in lines)
        print('%s: %d 页重组完成, %d KB' % (name, len(pgmap), chars // 1024))
    print()
    if missing_all:
        print('待补 OCR:', missing_all)
    else:
        print('全部 16 科重组完成')


if __name__ == '__main__':
    main()
