import { useCallback, useRef } from 'react';
import { useChatStore } from '@/store/chat';
import { authStorage } from '@/lib/api';
import { readSse } from '@/lib/sse';

/**
 * 智能对话 SSE 流式发送
 * POST /api/chat_stream body {Id, Question}
 * 事件: name="message", data=SseMessage JSON {type: content|error|done, data}
 * 兼容 heartbeat (data: ping 非 JSON, 由 readSse 忽略)
 */
export function useStreamChat(onError?: (msg: string) => void) {
  const sessionId = useChatStore((s) => s.sessionId);
  const pushMessage = useChatStore((s) => s.pushMessage);
  const updateLastMessage = useChatStore((s) => s.updateLastMessage);
  const setStreaming = useChatStore((s) => s.setStreaming);

  const abortRef = useRef<AbortController | null>(null);

  const abort = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const send = useCallback(
    async (question: string): Promise<{ ok: boolean; error?: string }> => {
      const controller = new AbortController();
      abortRef.current = controller;
      setStreaming(true);
      pushMessage({ role: 'user', content: question });
      pushMessage({ role: 'assistant', content: '' });

      let full = '';
      let errorMsg: string | undefined;
      let done = false;

      try {
        await readSse(
          '/api/chat_stream',
          { Id: sessionId, Question: question },
          authStorage.token,
          {
            signal: controller.signal,
            autoStopOnDone: true,
            onEvent: (_name, data) => {
              const m = data as { type?: string; data?: unknown };
              if (!m || typeof m !== 'object') return;
              if (m.type === 'content') {
                const chunk = typeof m.data === 'string' ? m.data : '';
                if (chunk) {
                  full += chunk;
                  updateLastMessage(full);
                }
              } else if (m.type === 'error') {
                errorMsg = typeof m.data === 'string' ? m.data : '服务返回错误';
                updateLastMessage(`[错误] ${errorMsg}`);
              } else if (m.type === 'done') {
                done = true;
              }
            },
          },
        );
      } catch (e) {
        const err = e as Error;
        if (err.name === 'AbortError') {
          if (full) {
            updateLastMessage(full + '\n\n*(已中断)*');
          } else {
            errorMsg = '已取消';
          }
        } else if (err.name === 'TimeoutError') {
          errorMsg = '请求超时，请稍后重试';
          if (full) updateLastMessage(full);
        } else {
          errorMsg = err.message || '连接中断';
          if (full) updateLastMessage(full);
        }
      } finally {
        if (!full && !errorMsg) {
          updateLastMessage('(无响应内容)');
        }
        if (errorMsg && !done && full) {
          // 已有内容则保留
        }
        setStreaming(false);
        abortRef.current = null;
      }

      if (errorMsg && !full) {
        onError?.(errorMsg);
        return { ok: false, error: errorMsg };
      }
      if (errorMsg) {
        onError?.(errorMsg);
      }
      return { ok: !errorMsg, error: errorMsg };
    },
    [sessionId, pushMessage, updateLastMessage, setStreaming, onError],
  );

  return { send, abort };
}
