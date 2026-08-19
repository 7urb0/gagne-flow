import { useCallback, useRef } from 'react';
import { authStorage } from '@/lib/api';
import { readSse } from '@/lib/sse';
import { useLessonStore, type StageName } from '@/store/lesson';
import type { LessonSseEvent } from '@/types';

export interface StreamLessonHandlers {
  /** 流式 chunk (阶段内聚合由调用方处理) */
  onChunk?: (stage: string, chunk: string) => void;
  /** 阶段完成 (stage:analysis|design|development|review|format) */
  onStageComplete?: (stage: StageName, content: string, extra?: { updated?: boolean; sessionId?: string }) => void;
  /** 意图澄清 */
  onClarify?: (questions: string, token: string) => void;
  /** copilot 分步确认 */
  onAwait?: (stage: string, token: string, content: string) => void;
  onError?: (msg: string) => void;
  onDone?: () => void;
}

/**
 * 教案生成 SSE 多阶段流
 * POST /api/lesson_plan
 * 事件(name=message, data 内 type): stage:content / stage:* / analysis_clarify / stage_await / error / done
 * heartbeat (data: ping) 由 readSse 自动忽略 (L6)
 */
export function useStreamLesson() {
  const setGenerating = useLessonStore((s) => s.setGenerating);
  const abortRef = useRef<AbortController | null>(null);

  const abort = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const run = useCallback(
    async (params: Record<string, unknown>, handlers: StreamLessonHandlers) => {
      const controller = new AbortController();
      abortRef.current = controller;
      setGenerating(true);

      try {
        await readSse('/api/lesson_plan', params, authStorage.token, {
          signal: controller.signal,
          autoStopOnDone: true,
          onEvent: (_name, data) => {
            const m = data as LessonSseEvent;
            if (!m || typeof m !== 'object') return;
            const type = (m as { type?: string }).type;

            if (type === 'stage:content') {
              const e = m as { stage: string; chunk?: string; content?: string };
              handlers.onChunk?.(e.stage, e.chunk || e.content || '');
            } else if (type && type.startsWith('stage:')) {
              const e = m as {
                stage: string;
                content?: string;
                updated?: boolean;
                sessionId?: string;
              };
              handlers.onStageComplete?.(
                e.stage as StageName,
                e.content || '',
                { updated: e.updated, sessionId: e.sessionId },
              );
            } else if (type === 'analysis_clarify') {
              const e = m as { questions: string; token: string };
              handlers.onClarify?.(e.questions || '', e.token || '');
            } else if (type === 'stage_await') {
              const e = m as { stage: string; token: string; content?: string };
              handlers.onAwait?.(e.stage || '', e.token || '', e.content || '');
            } else if (type === 'error') {
              const e = m as { data?: string };
              handlers.onError?.(e.data || '生成失败');
            } else if (type === 'done') {
              handlers.onDone?.();
            }
          },
        });
      } catch (e) {
        const err = e as Error;
        if (err.name !== 'AbortError') {
          handlers.onError?.(err.message || '连接中断，请稍后重试');
        }
      } finally {
        setGenerating(false);
        abortRef.current = null;
      }
    },
    [setGenerating],
  );

  return { run, abort };
}
