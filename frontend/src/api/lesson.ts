import { apiFetch, authStorage, redirectToLogin, tryRefresh } from '@/lib/api';
import type { LessonPlanRequest } from '@/types';

export interface PlaceholderResponse {
  placeholder?: string;
}

export async function fetchPlaceholder(subject: string): Promise<string | null> {
  try {
    const data = await apiFetch<PlaceholderResponse>(
      `/api/lesson_plan/placeholder/${encodeURIComponent(subject)}`,
    );
    return data?.placeholder || null;
  } catch {
    return null;
  }
}

export async function submitScore(
  sessionId: string,
  score: number,
  feedback: string,
): Promise<void> {
  await apiFetch('/api/lesson_plan/score', {
    method: 'POST',
    body: { sessionId, score: String(score), feedback },
    timeoutMs: 15000,
  });
}

export async function submitClarify(token: string, answer: string): Promise<void> {
  await apiFetch('/api/lesson_plan/clarify', {
    method: 'POST',
    body: { token, answer },
    timeoutMs: 15000,
  });
}

export interface CopilotActionParams {
  token: string;
  action: 'continue' | 'revise' | 'terminate';
  stage: string;
  instruction?: string;
}

export async function submitCopilotAction(params: CopilotActionParams): Promise<void> {
  await apiFetch('/api/lesson_plan/action', {
    method: 'POST',
    body: {
      token: params.token,
      action: params.action,
      stage: params.stage,
      instruction: params.instruction || '',
    },
    timeoutMs: 15000,
  });
}

/** L2: PDF 下载走后端端点 (带鉴权 header + 401 自动刷新重试) */
export function buildPdfUrl(sessionId: string): string {
  return `/api/lesson_plan/pdf/${encodeURIComponent(sessionId)}`;
}

export async function downloadPdf(sessionId: string): Promise<void> {
  const buildInit = (token: string | null): RequestInit => ({
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });

  const doFetch = (token: string | null) => fetch(buildPdfUrl(sessionId), buildInit(token));

  let res = await doFetch(authStorage.token);

  // 401/403: 用 refreshToken 刷新一次后重试 (与 apiFetch 行为一致)
  if ((res.status === 401 || res.status === 403) && authStorage.refreshToken) {
    const ok = await tryRefresh();
    if (ok) {
      res = await doFetch(authStorage.token);
    } else {
      redirectToLogin();
      throw new Error('登录已过期，请重新登录');
    }
  }

  if (!res.ok) {
    let msg = `PDF 下载失败 (${res.status})`;
    try {
      const data = (await res.json()) as { error?: string };
      if (data.error) msg = data.error;
    } catch {
      /* ignore */
    }
    throw new Error(msg);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `lesson_plan_${sessionId}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export type { LessonPlanRequest };
