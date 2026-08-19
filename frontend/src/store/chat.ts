import { create } from 'zustand';
import type { ChatHistoryItem, ChatMessage } from '@/types';

interface ChatState {
  /** 当前会话 id (前端生成 sess_xxx) */
  sessionId: string;
  history: ChatHistoryItem[];
  messages: ChatMessage[];
  streaming: boolean;
  /** 侧边栏移动端是否收起 */
  sidebarOpen: boolean;
  setSessionId: (id: string) => void;
  setHistory: (items: ChatHistoryItem[]) => void;
  setMessages: (msgs: ChatMessage[]) => void;
  pushMessage: (m: ChatMessage) => void;
  updateLastMessage: (content: string) => void;
  setStreaming: (v: boolean) => void;
  toggleSidebar: () => void;
  setSidebarOpen: (v: boolean) => void;
  resetChat: () => void;
}

function genId(): string {
  return 'sess_' + Math.random().toString(36).slice(2, 9) + '_' + Date.now();
}

export const useChatStore = create<ChatState>((set) => ({
  sessionId: genId(),
  history: [],
  messages: [],
  streaming: false,
  sidebarOpen: true,

  setSessionId: (id) => set({ sessionId: id }),
  setHistory: (items) => set({ history: items }),
  setMessages: (msgs) => set({ messages: msgs }),
  pushMessage: (m) => set((s) => ({ messages: [...s.messages, m] })),
  updateLastMessage: (content) =>
    set((s) => {
      const msgs = [...s.messages];
      if (msgs.length === 0) return { messages: msgs };
      const last = { ...msgs[msgs.length - 1], content };
      msgs[msgs.length - 1] = last;
      return { messages: msgs };
    }),
  setStreaming: (v) => set({ streaming: v }),
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
  setSidebarOpen: (v) => set({ sidebarOpen: v }),
  resetChat: () => set({ sessionId: genId(), messages: [], streaming: false }),
}));
