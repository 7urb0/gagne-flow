/**
 * SSE 流式读取封装
 * - 逐行解析 data:, 兼容后端 heartbeat (event: heartbeat + data: ping, 非 JSON)
 * - 忽略空行 / [DONE] / 非 JSON 心跳
 * - 支持 AbortSignal (取消生成)
 * - 支持超时
 */

export interface SseHandle {
  abort: () => void;
}

export interface SseOptions {
  onEvent: (eventName: string, data: unknown) => void;
  /** 兼容只有 data: 行的裸 SSE (例如某些事件无 event 名) */
  onData?: (raw: string) => void;
  signal?: AbortSignal;
  timeoutMs?: number;
  /** 收到 done 事件时自动 abort (默认 true) */
  autoStopOnDone?: boolean;
}

/**
 * 发起 POST SSE 请求并持续消费。
 * 当流正常结束 / done 事件 / abort / 超时 时 resolve。
 * 解析失败的事件(非 JSON)忽略并继续。
 */
export async function readSse(
  url: string,
  body: unknown,
  token: string | null,
  options: SseOptions,
): Promise<void> {
  const { onEvent, onData, signal, timeoutMs = 0, autoStopOnDone = true } = options;

  const controller = new AbortController();
  const onOuterAbort = () => controller.abort();
  signal?.addEventListener('abort', onOuterAbort);

  let timer: ReturnType<typeof setTimeout> | undefined;
  if (timeoutMs > 0) {
    timer = setTimeout(() => controller.abort(new DOMException('timeout', 'TimeoutError')), timeoutMs);
  }

  try {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const res = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: controller.signal,
    });

    if (!res.ok) {
      let msg = `服务器返回 ${res.status}`;
      try {
        const data = (await res.json()) as { error?: string; message?: string };
        msg = data.error || data.message || msg;
      } catch {
        /* ignore */
      }
      throw new Error(msg);
    }
    if (!res.body) throw new Error('响应流不可用');

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });

      const lines = buf.split('\n');
      buf = lines.pop() || '';

      let eventName = 'message';
      let dataLines: string[] = [];

      const dispatch = () => {
        const raw = dataLines.join('\n').trim();
        dataLines = [];
        if (!raw || raw === '[DONE]') return;
        onData?.(raw);
        let parsed: unknown;
        try {
          parsed = JSON.parse(raw);
        } catch {
          // heartbeat ping 等非 JSON 数据: 忽略, 不报错 (L6)
          return;
        }
        onEvent(eventName, parsed);
        if (autoStopOnDone && isDoneEvent(eventName, parsed)) {
          controller.abort();
        }
      };

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) {
          dispatch();
          continue;
        }
        if (trimmed.startsWith(':')) continue; // comment
        if (trimmed.startsWith('event:')) {
          eventName = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
          dataLines.push(trimmed.slice(5).trimStart());
        } else if (trimmed.startsWith('id:')) {
          /* ignore */
        } else if (trimmed.startsWith('retry:')) {
          /* ignore */
        }
      }
      dispatch();
    }
  } finally {
    if (timer) clearTimeout(timer);
    signal?.removeEventListener('abort', onOuterAbort);
  }
}

function isDoneEvent(eventName: string, parsed: unknown): boolean {
  if (eventName === 'done') return true;
  if (eventName === 'message' && parsed && typeof parsed === 'object') {
    const t = (parsed as { type?: string }).type;
    return t === 'done';
  }
  return false;
}
