/**
 * apiFetch 封装:
 * - 自动携带 Bearer token
 * - 401 用 refreshToken 刷新一次后重试
 * - 刷新失败/无 token 时清空登录态并跳转 /login
 */

import type { ApiErrorBody } from '@/types';

const TOKEN_KEY = 'token';
const REFRESH_KEY = 'refreshToken';
const USERNAME_KEY = 'username';

export const authStorage = {
  get token() {
    return localStorage.getItem(TOKEN_KEY);
  },
  get refreshToken() {
    return localStorage.getItem(REFRESH_KEY);
  },
  get username() {
    return localStorage.getItem(USERNAME_KEY);
  },
  save(token: string, refreshToken: string, username: string) {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(REFRESH_KEY, refreshToken);
    localStorage.setItem(USERNAME_KEY, username);
  },
  saveToken(token: string) {
    localStorage.setItem(TOKEN_KEY, token);
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USERNAME_KEY);
  },
};

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function tryRefresh(): Promise<boolean> {
  const refreshToken = authStorage.refreshToken;
  if (!refreshToken) return false;
  try {
    const res = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) return false;
    const data = (await res.json()) as { token: string };
    if (!data.token) return false;
    authStorage.saveToken(data.token);
    return true;
  } catch {
    return false;
  }
}

export function redirectToLogin() {
  authStorage.clear();
  if (window.location.pathname !== '/login') {
    window.location.href = '/login';
  }
}

export interface ApiFetchOptions extends Omit<RequestInit, 'body'> {
  body?: BodyInit | Record<string, unknown> | null;
  /** 请求超时(ms), 默认 30000; 传 Infinity 表示不设超时(SSE) */
  timeoutMs?: number;
}

export async function apiFetch<T = unknown>(
  url: string,
  options: ApiFetchOptions = {},
): Promise<T> {
  const { timeoutMs = 30000, headers: extraHeaders, body, ...rest } = options;

  const buildInit = (token: string | null): RequestInit => {
    const headers = new Headers(extraHeaders as HeadersInit | undefined);
    if (token) headers.set('Authorization', `Bearer ${token}`);
    if (body && !(body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    return {
      ...rest,
      headers,
      body: body instanceof FormData || body == null ? (body as BodyInit | null) : JSON.stringify(body),
    };
  };

  const doFetch = async (token: string | null, signal?: AbortSignal) => {
    const init = buildInit(token);
    if (signal) init.signal = signal;
    return fetch(url, init);
  };

  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  if (Number.isFinite(timeoutMs)) {
    timer = setTimeout(() => controller.abort(new DOMException('timeout', 'TimeoutError')), timeoutMs);
  }

  try {
    let res = await doFetch(authStorage.token, controller.signal);

    // 401/403: 尝试刷新一次
    if ((res.status === 401 || res.status === 403) && authStorage.refreshToken) {
      const ok = await tryRefresh();
      if (ok) {
        res = await doFetch(authStorage.token, controller.signal);
      } else {
        redirectToLogin();
        throw new ApiError(res.status, '登录已过期，请重新登录');
      }
    }

    if (!res.ok) {
      let msg = `请求失败 (${res.status})`;
      try {
        const data = (await res.json()) as ApiErrorBody;
        msg = data.error || data.message || msg;
      } catch {
        /* 非 JSON 错误体 */
      }
      throw new ApiError(res.status, msg);
    }

    // 204 无内容
    if (res.status === 204) return undefined as T;

    const ct = res.headers.get('content-type') || '';
    if (ct.includes('application/json')) {
      return (await res.json()) as T;
    }
    return (await res.text()) as unknown as T;
  } finally {
    if (timer) clearTimeout(timer);
  }
}
