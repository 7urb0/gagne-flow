import { useMemo, useState } from 'react';
import { Download, Edit3, FileText, ListOrdered, Pencil, Save, X } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { downloadPdf } from '@/api/lesson';
import type { LessonResultItem } from '@/store/lesson';
import { sanitizeLessonHtml } from '@/lib/lessonHtml';
import { parseScore, parseScoreLenient } from '@/lib/score';
import { QualityScore } from '@/components/lesson/QualityScore';
import { ScorePanel } from '@/components/lesson/ScorePanel';
import { cn } from '@/lib/utils';

interface Section {
  id: number;
  title: string;
  html: string;
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** 解析教案 HTML 为章节 (基于 h2/h3, 无标题章节自动编号) */
function parseSections(html: string): Section[] {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const container = doc.body;
  const sections: Section[] = [];
  let current: { title: string; parts: string[] } | null = null;
  let noTitleCount = 0;

  const flush = () => {
    if (current && current.parts.length > 0) {
      sections.push({
        id: sections.length,
        title: current.title || `章节 ${sections.length + 1}`,
        html: current.parts.join(''),
      });
    }
    current = null;
  };

  Array.from(container.children).forEach((el) => {
    const tag = el.tagName.toLowerCase();
    if (tag === 'h2' || tag === 'h3') {
      flush();
      current = {
        title: (el.textContent || '').trim() || `章节 ${sections.length + 1}`,
        parts: [el.outerHTML],
      };
    } else if (tag === 'div' && el.className === 'hitl-warning') {
      flush();
      sections.push({ id: sections.length, title: '系统提示', html: el.outerHTML });
    } else {
      if (!current) {
        noTitleCount += 1;
        current = { title: `前言`, parts: [] };
      }
      current.parts.push(el.outerHTML);
    }
  });
  flush();
  if (sections.length === 0) {
    sections.push({ id: 0, title: '教案内容', html });
  }
  return sections;
}

/**
 * 教案结果工作台
 * B2: 渲染前 DOMPurify 消毒 (scoped .lesson-html)
 * L1: 显式"编辑"按钮 + 保存/取消, 仅本地预览不回写
 * L2: 打印/下载走后端 PDF 端点 (真实 sessionId + 鉴权)
 */
export function Workbench({
  result,
  onClose,
}: {
  result: LessonResultItem;
  onClose: () => void;
}) {
  const [sections, setSections] = useState<Section[]>(() => parseSections(result.html));
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editText, setEditText] = useState('');
  const [downloading, setDownloading] = useState(false);

  // E1: LLM 质量评估容错解析 (标准 + 宽松兜底), 失败给明确提示
  const llmScore = useMemo(() => {
    if (!result.reviewText) return null;
    return parseScoreLenient(result.reviewText) ?? parseScore(result.reviewText);
  }, [result.reviewText]);

  const outline = useMemo(
    () => sections.map((s, i) => ({ id: s.id, title: s.title, index: i + 1 })),
    [sections],
  );

  const scrollToSection = (id: number) => {
    document.getElementById(`wb-section-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const startEdit = (sec: Section) => {
    const doc = new DOMParser().parseFromString(sec.html, 'text/html');
    setEditingId(sec.id);
    setEditText(doc.body.textContent || '');
  };

  const saveEdit = (sec: Section) => {
    const text = editText.trim();
    if (!text) {
      toast.warning('内容不能为空');
      return;
    }
    setSections((prev) =>
      prev.map((s) =>
        s.id === sec.id ? { ...s, html: `<p>${escapeHtml(text).replace(/\n/g, '<br>')}</p>` } : s,
      ),
    );
    setEditingId(null);
    toast.success('已保存到本地预览', {
      description: '当前仅本地预览，不会回写服务器',
    });
  };

  const doDownloadPdf = async () => {
    setDownloading(true);
    try {
      await downloadPdf(result.sessionId);
      toast.success('PDF 已开始下载');
    } catch (e) {
      toast.error('PDF 下载失败', { description: (e as Error).message });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="flex h-full flex-col bg-background">
      <header className="flex h-13 shrink-0 items-center justify-between border-b px-5 py-3">
        <div className="flex items-center gap-2 text-sm font-bold">
          <FileText className="h-4 w-4 text-primary" />
          教案工作台
          <span className="ml-2 rounded bg-muted px-2 py-0.5 text-[11px] font-normal text-muted-foreground">
            {result.title}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => void doDownloadPdf()}
            disabled={downloading}
            aria-label="下载教案 PDF"
          >
            <Download className="h-4 w-4" />
            {downloading ? '生成中...' : '打印/下载 PDF'}
          </Button>
          <Button variant="secondary" size="sm" onClick={onClose}>
            返回
          </Button>
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        {/* 大纲 */}
        <aside className="w-52 shrink-0 overflow-y-auto border-r bg-muted/40 p-3">
          <div className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            <ListOrdered className="h-3.5 w-3.5" />
            大纲
          </div>
          {outline.map((o) => (
            <button
              key={o.id}
              type="button"
              onClick={() => scrollToSection(o.id)}
              className="mb-0.5 flex w-full items-start gap-1.5 rounded-md px-2 py-1.5 text-left text-xs text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary"
            >
              <span className="shrink-0 font-mono text-[10px] leading-4 text-primary/60">
                {o.index}.
              </span>
              <span className="line-clamp-2 leading-4">{o.title}</span>
            </button>
          ))}
        </aside>

        {/* 内容 */}
        <div className="min-w-0 flex-1 overflow-y-auto px-6 py-5 md:px-10">
          <div className="mx-auto max-w-3xl">
            <div className="mb-4 flex items-center justify-between rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
              <span>章节编辑为本地预览，不会回写服务器</span>
              <Pencil className="h-3.5 w-3.5" />
            </div>

            {sections.map((sec, idx) => (
              <section
                key={sec.id}
                id={`wb-section-${sec.id}`}
                className={cn(
                  'group relative mb-3 rounded-lg border border-transparent px-2 py-3 transition-colors hover:border-border hover:bg-muted/30',
                  editingId === sec.id && 'border-primary/40 bg-primary/5',
                )}
              >
                <div className="lesson-html" dangerouslySetInnerHTML={{ __html: sanitizeLessonHtml(sec.html) }} />
                <div
                  className={cn(
                    'absolute right-2 top-2 flex gap-1 opacity-0 transition-opacity',
                    'group-hover:opacity-100',
                    editingId === sec.id && 'opacity-100',
                  )}
                >
                  {editingId === sec.id ? (
                    <>
                      <Button size="sm" className="h-7 px-2 text-xs" onClick={() => saveEdit(sec)}>
                        <Save className="h-3.5 w-3.5" />
                        保存
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        className="h-7 px-2 text-xs"
                        onClick={() => setEditingId(null)}
                      >
                        <X className="h-3.5 w-3.5" />
                        取消
                      </Button>
                    </>
                  ) : (
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-7 px-2 text-xs"
                      onClick={() => startEdit(sec)}
                      aria-label={`编辑章节 ${idx + 1}`}
                    >
                      <Edit3 className="h-3.5 w-3.5" />
                      编辑
                    </Button>
                  )}
                </div>
                {editingId === sec.id && (
                  <textarea
                    value={editText}
                    onChange={(e) => setEditText(e.target.value)}
                    rows={8}
                    aria-label={`章节 ${idx + 1} 编辑内容`}
                    className="mt-2 w-full rounded-lg border border-primary/40 bg-background p-3 font-mono text-xs leading-relaxed outline-none focus:ring-2 focus:ring-primary/30"
                  />
                )}
              </section>
            ))}

            {/* E1: 顺序 = 教案全文 → LLM 质量评估 → 用户星级评分 */}
            <div className="mt-8 border-t pt-4">
              <QualityScore score={llmScore} raw={result.reviewText} />
              <ScorePanel sessionId={result.sessionId} />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
