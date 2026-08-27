import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, CheckCheck, Download, Edit3, FileText, ListOrdered, Pencil, Save, X } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { activateLesson, downloadPdf } from '@/api/lesson';
import type { LessonResultItem } from '@/store/lesson';
import { useLessonStore } from '@/store/lesson';
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

  // 2026-08-21: 评分窗口"延迟激活" — 教案展示给用户即激活正式评分窗口(后端从此刻计时)。
  // 重新生成场景下, 新教案被暂存时窗口不开始计时, 用户处理完旧教案打开新教案才计时。
  useEffect(() => {
    if (result.sessionId) {
      void activateLesson(result.sessionId);
    }
  }, [result.sessionId]);

  // 2026-08-21 Layer2: 优先消费后端 stage:review 事件下发的结构化评分(单一数据源),
  // 前端不再 re-parse review 文本; 仅当结构化缺失时才回退文本解析(兼容旧版本教案)。
  const llmScore = useMemo(() => {
    if (result.llmScore != null) {
      const total = result.llmScore;
      if (result.reviewDimensions) {
        return { total, dims: result.reviewDimensions };
      }
      // 有总分无维度: 均摊(与 parseScore 行为一致)
      const dims = {} as import('@/types').ScoreDimensions;
      for (const k of ['clarity', 'accuracy', 'strategy', 'alignment', 'format'] as const) {
        dims[k] = Math.round(total / 5);
      }
      return { total, dims };
    }
    if (!result.reviewText) return null;
    return parseScoreLenient(result.reviewText) ?? parseScore(result.reviewText);
  }, [result.llmScore, result.reviewDimensions, result.reviewText]);

  // 2026-08-21: HITL 人工复核确认闸门 —— 后端将质量警告注入 html(hitl-warning),
  // 分数不达标但用户显式"保留并使用"时, 允许保留(个人库), 但不可进入共享知识库。
  const needsHumanReview = useMemo(
    () => result.html.includes('hitl-warning'),
    [result.html],
  );
  const kept = useLessonStore((s) => s.keepDecided[result.sessionId] ?? false);
  const setKeepDecided = useLessonStore((s) => s.setKeepDecided);
  const removeResult = useLessonStore((s) => s.removeResult);
  const completed = useLessonStore((s) => s.completedSids[result.sessionId] ?? false);
  const markCompleted = useLessonStore((s) => s.markCompleted);

  /** 2026-08-21: 用户显式"处理完毕" — 不评分也可放行重新生成闸门 */
  const doneWithLesson = () => {
    markCompleted(result.sessionId);
    toast.success('已确认处理完毕', {
      description: '该教案已标记完成，可继续查看新生成的教案',
    });
  };

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
    const next = sections.map((s) =>
      s.id === sec.id ? { ...s, html: `<p>${escapeHtml(text).replace(/\n/g, '<br>')}</p>` } : s,
    );
    setSections(next);
    // 持久化到 store 的该教案 html, 切换多份结果后再次打开仍保留编辑(L1 不丢)
    const newHtml = next.map((s) => s.html).join('');
    useLessonStore.getState().updateResultHtml(result.sessionId, newHtml);
    setEditingId(null);
    toast.success('已保存到本地预览', {
      description: '本地预览已更新（不回写服务器），切换教案后仍在',
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
            {/* 2026-08-21: HITL 人工复核确认闸门 —— 低分/异常教案需用户显式"保留或使用/放弃" */}
            {needsHumanReview && !kept && (
              <div className="mb-4 rounded-xl border-2 border-amber-300 bg-amber-50 p-4">
                <div className="flex items-center gap-2 text-sm font-bold text-amber-800">
                  <AlertTriangle className="h-4 w-4" />
                  该教案经系统检测可能存在质量问题，需人工复核
                </div>
                <p className="mt-1 text-xs leading-relaxed text-amber-700">
                  评分偏低或内容异常。你可以选择「保留并使用」（仅保存在你的个人库，不会进入共享知识库），或「放弃此教案」。
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    onClick={() => {
                      setKeepDecided(result.sessionId, true);
                      // 2026-08-21: "保留并使用"即视为该教案处理完毕, 释放重新生成闸门
                      markCompleted(result.sessionId);
                    }}
                  >
                    保留并使用
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    className="border-red-300 text-red-600 hover:bg-red-50 hover:text-red-700"
                    onClick={() => {
                      removeResult(result.sessionId);
                      onClose();
                    }}
                  >
                    放弃此教案
                  </Button>
                </div>
              </div>
            )}

            <div className="mb-4 flex items-center justify-between rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
              <span>章节编辑为本地预览，不会回写服务器</span>
              <Pencil className="h-3.5 w-3.5" />
            </div>

            {sections.map((sec, idx) =>
              // 已"保留"后隐藏系统注入的红色警告块(仅视觉, 不回写服务器)
              sec.html.includes('hitl-warning') && kept ? null : (
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
              <ScorePanel
                sessionId={result.sessionId}
                onScored={() => useLessonStore.getState().markCompleted(result.sessionId)}
              />
              {/* 2026-08-21: 显式"处理完毕"确认 — 不评分也可放行重新生成闸门(处理完旧教案再看新教案) */}
              {!completed && (
                <div className="mb-4 flex items-center justify-between rounded-xl border border-dashed border-primary/30 bg-muted/30 p-3 text-sm">
                  <span className="text-xs text-muted-foreground">
                    若您已处理完这份教案（不再需要评分），请确认处理完毕，以便查看新生成的教案。
                  </span>
                  <Button size="sm" variant="outline" onClick={doneWithLesson}>
                    <CheckCheck className="h-3.5 w-3.5" />
                    确认处理完毕
                  </Button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
