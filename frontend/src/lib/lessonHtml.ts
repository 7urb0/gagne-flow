/**
 * 教案 HTML 处理:
 * - wrapSections: 按 h2/h3 切分成可编辑章节 (工作台大纲导航用)
 * - 渲染前一律 DOMPurify 消毒 (B2 XSS 防护)
 */

import DOMPurify from 'dompurify';

/** 按 h2/h3 边界把完整教案 HTML 拆成 <section> 包裹的章节 */
export function wrapSections(html: string): string {
  if (!html) return '';
  let wrapped = html.replace(
    /<(h[23])[^>]*>(.*?)<\/\1>/gi,
    '</section><$1>$2</$1><section>',
  );
  wrapped = wrapped.replace(/^<\/section>/, '').replace(/<section>$/, '');
  return wrapped || '<section>' + html + '</section>';
}

/** DOMPurify 消毒 (禁止 script / on* 事件, 允许教案常用标签) */
export function sanitizeLessonHtml(html: string): string {
  if (!html) return '';
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form'],
    FORBID_ATTR: ['onerror', 'onclick', 'onload', 'onmouseover', 'onfocus', 'style'],
    ADD_ATTR: ['target', 'rel'],
  });
}

/** 从教案 HTML 提取 h2/h3 大纲 */
export function extractOutline(html: string): { tag: string; text: string }[] {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const outline: { tag: string; text: string }[] = [];
  doc.querySelectorAll('h2, h3').forEach((h) => {
    const text = (h.textContent || '').trim();
    if (text) outline.push({ tag: h.tagName.toLowerCase(), text });
  });
  return outline;
}
