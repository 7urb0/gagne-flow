import { apiFetch } from '@/lib/api';
import type { AuthMessage, LoginResponse } from '@/types';

export async function loginApi(username: string, password: string): Promise<LoginResponse> {
  return apiFetch<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: { username, password },
    timeoutMs: 15000,
  });
}

export async function registerApi(username: string, password: string): Promise<AuthMessage> {
  return apiFetch<AuthMessage>('/api/auth/register', {
    method: 'POST',
    body: { username, password },
    timeoutMs: 15000,
  });
}

export async function logoutApi(): Promise<void> {
  await apiFetch<AuthMessage>('/api/auth/logout', {
    method: 'POST',
    timeoutMs: 10000,
  });
}
