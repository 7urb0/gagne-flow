import { create } from 'zustand';
import { apiFetch, authStorage, redirectToLogin } from '@/lib/api';
import type { AuthMessage, LoginResponse } from '@/types';

interface AuthState {
  token: string | null;
  refreshToken: string | null;
  username: string | null;
  initialized: boolean;
  init: () => void;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  token: null,
  refreshToken: null,
  username: null,
  initialized: false,

  init: () => {
    set({
      token: authStorage.token,
      refreshToken: authStorage.refreshToken,
      username: authStorage.username,
      initialized: true,
    });
  },

  login: async (username, password) => {
    const data = await apiFetch<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: { username, password },
      timeoutMs: 15000,
    });
    authStorage.save(data.token, data.refreshToken, data.username);
    set({ token: data.token, refreshToken: data.refreshToken, username: data.username });
  },

  register: async (username, password) => {
    await apiFetch<AuthMessage>('/api/auth/register', {
      method: 'POST',
      body: { username, password },
      timeoutMs: 15000,
    });
  },

  logout: async () => {
    try {
      await apiFetch<AuthMessage>('/api/auth/logout', {
        method: 'POST',
        timeoutMs: 10000,
      });
    } catch {
      // 登出失败不阻塞本地清理
    }
    authStorage.clear();
    set({ token: null, refreshToken: null, username: null });
    redirectToLogin();
  },
}));
