import { apiFetch } from '@/lib/api';
import type { ChatHistoryItem, ChatMessage, PoolStatus } from '@/types';

export async function fetchHistory(): Promise<ChatHistoryItem[]> {
  const data = await apiFetch<ChatHistoryItem[]>('/api/chat/history');
  return data ?? [];
}

export async function fetchMessages(sessionId: string): Promise<ChatMessage[]> {
  const data = await apiFetch<ChatMessage[]>(`/api/chat/messages/${encodeURIComponent(sessionId)}`);
  return data ?? [];
}

export async function registerHistory(sessionId: string, title: string): Promise<void> {
  await apiFetch('/api/chat/history/register', {
    method: 'POST',
    body: { sessionId, title },
  });
}

/** L3: 删除会话同步后端 */
export async function clearChatHistory(sessionId: string): Promise<void> {
  await apiFetch('/api/chat/clear', {
    method: 'POST',
    body: { id: sessionId },
  });
}

export async function fetchPoolStatus(): Promise<PoolStatus | null> {
  try {
    return await apiFetch<PoolStatus>('/api/chat/pool-status');
  } catch {
    return null;
  }
}
