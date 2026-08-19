import { useCallback, useEffect, useRef, useState } from 'react';
import { BookOpenText, Eye, FileText, Loader2, X } from 'lucide-react';
import { toast } from 'sonner';
import { useLessonStore } from '@/store/lesson';
import { useStreamLesson } from '@/hooks/useStreamLesson';
import { LessonForm } from '@/components/lesson/LessonForm';
import { StageProgress } from '@/components/lesson/StageProgress';
import { Workbench } from '@/components/lesson/Workbench';
import { ClarifyPanel } from '@/components/lesson/ClarifyPanel';
import { CopilotConfirm } from '@/components/lesson/CopilotConfirm';
import { Markdown } from '@/components/chat/Markdown';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { STAGE_LABELS, type StageName } from '@/store/lesson';
import type { LessonPlanRequest } from '@/types';
import { cn } from '@/lib/utils';

interface StageCard {
  stage: StageName;
  content: string;
  streaming: boolean;
}

interface AwaitPanel {
  key: number;
  stage: string;
  token: string;
  content: string;
}

/** 教案生成页 /lesson */
export function LessonPage() {
  const {
    generating,
    setStageStatus,
    setCurrentStage,
    setActiveSessionId,
    setLastError,
    addResult,
    updateResultHtml,
    results,
    activeResultIndex,
    setActiveResultIndex,
  } = useLessonStore();

  const { run, abort } = useStreamLesson();

  // L5: 阶段聚合卡片
  const [stageCards, setStageCards] = useState<StageCard[]>([]);
  const [clarify, setClarify] = useState<{ questions: string; token: string } | null>(null);
  const [awaits, setAwaits] = useState<AwaitPanel[]>([]);
  const [workbenchOpen, setWorkbenchOpen] = useState(false);
  const [previewHtml, setPreviewHtml] = useState<string | null>(null);

  // 当前生成过程的最终 html (updated 事件覆盖)
  const finalHtmlRef = useRef<string | null>(null);
  const previewRef = useRef<string | null>(null);
  const currentSidRef = useRef<string | null>(null);
  const reviewTextRef = useRef<string | null>(null);
  const addedRef = useRef<string | null>(null);
  const awaiterKey = useRef(0);

  const resetRunState = () => {
    setStageCards([]);
    setClarify(null);
    setAwaits([]);
    setPreviewHtml(null);
    finalHtmlRef.current = null;
    previewRef.current = null;
    currentSidRef.current = null;
    reviewTextRef.current = null;
    addedRef.current = null;
    useLessonStore.getState().reset();
  };

  const commitResult = useCallback(
    (sid: string, html: string, reviewText?: string | null) => {
      if (addedRef.current === sid) return;
      addedRef.current = sid;
      addResult({
        sessionId: sid,
        html,
        title: `教案 ${useLessonStore.getState().results.length + 1}`,
        createdAt: Date.now(),
        userScored: false,
        reviewText: reviewText || undefined,
      });
    },
    [addResult],
  );

  const handleSubmit = useCallback(
    async (params: LessonPlanRequest) => {
      resetRunState();
      setLastError(null);

      await run(params as unknown as Record<string, unknown>, {
        onChunk: (stage, chunk) => {
          const s = stage as StageName;
          setCurrentStage(s);
          setStageStatus(s, 'running');
          setStageCards((prev) => {
            const idx = prev.findIndex((c) => c.stage === s);
            if (idx >= 0) {
              const next = [...prev];
              next[idx] = { ...next[idx], content: next[idx].content + chunk, streaming: true };
              return next;
            }
            return [...prev, { stage: s, content: chunk, streaming: true }];
          });
        },
        onStageComplete: (stage, content, extra) => {
          setStageStatus(stage, 'done');

          if (stage === 'format') {
            // B1: 捕获后端真实 sessionId
            if (extra?.sessionId) {
              currentSidRef.current = extra.sessionId;
              setActiveSessionId(extra.sessionId);
            }
            if (extra?.updated) {
              // Review 完成后的完整 HTML
              finalHtmlRef.current = content;
              previewRef.current = content;
              setPreviewHtml(content);
              const sid = currentSidRef.current || extra.sessionId;
              if (sid) {
                commitResult(sid, content, reviewTextRef.current);
              }
            } else {
              // 首次 format: 预览 (截断 500), 等待 updated 覆盖
              previewRef.current = content;
              setPreviewHtml(content);
            }
          }

          if (stage === 'review') {
            reviewTextRef.current = content;
            // 已有完整 html 则刷新结果中的评估
            const sid = currentSidRef.current;
            if (sid && finalHtmlRef.current) {
              commitResult(sid, finalHtmlRef.current, content);
            }
          }

          // 阶段卡片标记完成
          setStageCards((prev) =>
            prev.map((c) => (c.stage === stage ? { ...c, streaming: false } : c)),
          );
        },
        onClarify: (questions, token) => {
          setClarify({ questions, token });
        },
        onAwait: (stage, token, content) => {
          awaiterKey.current += 1;
          setAwaits((prev) => [...prev, { key: awaiterKey.current, stage, token, content }]);
        },
        onError: (msg) => {
          setLastError(msg);
          toast.error('教案生成失败', { description: msg });
        },
        onDone: () => {
          // 降级场景: 无 updated 完整 html 时用预览兜底
          const sid = currentSidRef.current;
          if (sid && !finalHtmlRef.current && previewRef.current) {
            commitResult(sid, previewRef.current, reviewTextRef.current);
          }
          if (!sid) {
            toast.warning('未获取到教案会话标识，可能无法下载 PDF 或评分');
          }
        },
      });
    },
    [run, setCurrentStage, setStageStatus, setActiveSessionId, setLastError, commitResult],
  );

  // 切换结果时自动关闭工作台再打开
  const openWorkbench = (idx: number) => {
    setActiveResultIndex(idx);
    setWorkbenchOpen(true);
  };

  const viewable = previewHtml != null || (results.length > 0 && generating === false);

  return (
    <div className="flex h-full flex-col">
      <header className="flex h-12 shrink-0 items-center justify-between border-b px-5 text-sm font-semibold">
        <span>教案生成</span>
        {generating && (
          <span className="flex items-center gap-1.5 text-xs font-normal text-muted-foreground">
            <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />
            正在生成...
          </span>
        )}
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {/* 表单 (生成中隐藏) */}
        {!generating && <LessonForm busy={generating} onSubmit={(p) => void handleSubmit(p)} />}

        {/* 生成中: 阶段流式卡片 (L5) */}
        {generating && (
          <div className="mx-auto max-w-3xl px-4 py-5 md:px-6">
            <div className="mb-4 flex items-center gap-2 text-sm font-bold">
              <BookOpenText className="h-4 w-4 text-primary" />
              正在生成教案
            </div>
            {clarify && <ClarifyPanel questions={clarify.questions} token={clarify.token} />}
            {stageCards.map((card) => (
              <div
                key={card.stage}
                className="mb-3 rounded-xl border bg-card p-4 shadow-sm animate-msg-in"
              >
                <div className="mb-2 flex items-center gap-2">
                  <Badge variant={card.streaming ? 'default' : 'secondary'}>
                    {STAGE_LABELS[card.stage]}
                  </Badge>
                  {card.streaming && <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />}
                </div>
                <div className="text-sm leading-relaxed" aria-live="polite">
                  <Markdown content={card.content || '...'} />
                </div>
              </div>
            ))}
            {awaits.map((a) => (
              <CopilotConfirm key={a.key} stage={a.stage} token={a.token} preview={a.content} />
            ))}
            {stageCards.length === 0 && awaits.length === 0 && !clarify && (
              <p className="text-sm text-muted-foreground">正在启动生成流水线...</p>
            )}
          </div>
        )}

        {/* 结果历史 (L4: 多份保留, 可切换) */}
        {!generating && results.length > 0 && (
          <div className="mx-auto max-w-3xl px-4 pb-6 md:px-6">
            <div className="mb-2 flex items-center justify-between">
              <h3 className="text-sm font-bold">已生成教案</h3>
              <span className="text-xs text-muted-foreground">共 {results.length} 份</span>
            </div>
            <div className="flex flex-col gap-2">
              {results.map((r, idx) => (
                <div
                  key={r.sessionId}
                  className={cn(
                    'flex cursor-pointer items-center gap-3 rounded-xl border bg-card px-4 py-3 shadow-sm transition-all hover:border-primary/50',
                    activeResultIndex === idx && 'border-primary/60 ring-1 ring-primary/20',
                  )}
                  onClick={() => openWorkbench(idx)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') openWorkbench(idx);
                  }}
                >
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <FileText className="h-4 w-4" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold">{r.title}</div>
                    <div className="text-xs text-muted-foreground">
                      {new Date(r.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
                      {r.llmScore != null && ` · LLM 评分 ${r.llmScore}`}
                    </div>
                  </div>
                  <Button variant="outline" size="sm" onClick={() => openWorkbench(idx)}>
                    <Eye className="h-3.5 w-3.5" />
                    查看
                  </Button>
                  <button
                    type="button"
                    aria-label={`删除教案 ${r.title}`}
                    className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
                    onClick={(e) => {
                      e.stopPropagation();
                      useLessonStore.setState((s) => ({
                        results: s.results.filter((x) => x.sessionId !== r.sessionId),
                        activeResultIndex: Math.max(0, s.activeResultIndex - 1),
                      }));
                    }}
                  >
                    <X className="h-4 w-4" />
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* 进度弹窗 (E4) */}
      <StageProgress
        viewable={viewable}
        onViewResult={() => {
          if (currentSidRef.current && finalHtmlRef.current) {
            commitResult(currentSidRef.current, finalHtmlRef.current, reviewTextRef.current);
          }
          const target = useLessonStore.getState().activeResultIndex;
          const lastIdx = useLessonStore.getState().results.length - 1;
          setActiveResultIndex(lastIdx >= 0 ? lastIdx : target);
          setWorkbenchOpen(true);
        }}
        onAbort={() => {
          abort();
          useLessonStore.getState().reset();
          setStageCards([]);
          setClarify(null);
        }}
      />

      {/* 工作台 (z-500) */}
      {workbenchOpen && results[activeResultIndex] && (
        <div className="fixed inset-0 z-[500] bg-background">
          <Workbench result={results[activeResultIndex]} onClose={() => setWorkbenchOpen(false)} />
        </div>
      )}
    </div>
  );
}
