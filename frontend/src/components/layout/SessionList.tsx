import { useCallback, useEffect, useState } from 'react';
import { MessageSquarePlus, Trash2, X } from 'lucide-react';
import { toast } from 'sonner';
import { useChatStore } from '@/store/chat';
import { useAuthStore } from '@/store/auth';
import { clearChatHistory, fetchHistory, fetchMessages, registerHistory } from '@/api/chat';
import { formatSessionTime } from '@/lib/format';
import { cn } from '@/lib/utils';

/**
 * 侧边栏会话列表 (浅色主题)
 * L3: 删除会话调用后端 /api/chat/clear + 二次确认 + 撤销提示
 */
export function SessionList() {
  const { sessionId, history, setHistory, setSessionId, setMessages, resetChat, messages } =
    useChatStore();
  const username = useAuthStore((s) => s.username);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const items = await fetchHistory();
      setHistory(items);
    } catch {
      /* 静默 */
    }
  }, [setHistory]);

  useEffect(() => {
    void load();
  }, [load]);

  const newChat = () => {
    if (messages.length > 0 && username) {
      const title = (messages[0]?.content || '新对话').slice(0, 30);
      void registerHistory(sessionId, title).catch(() => undefined);
    }
    resetChat();
  };

  const switchSession = async (id: string) => {
    if (id === sessionId) return;
    if (messages.length > 0 && username) {
      const title = (messages[0]?.content || '新对话').slice(0, 30);
      void registerHistory(sessionId, title).catch(() => undefined);
    }
    setSessionId(id);
    try {
      const msgs = await fetchMessages(id);
      setMessages(msgs);
    } catch (e) {
      toast.error('加载会话失败', { description: (e as Error).message });
    }
  };

  const doDelete = async (id: string) => {
    try {
      await clearChatHistory(id); // L3: 同步后端
      setHistory(history.filter((h) => h.sessionId !== id));
      toast.success('会话已删除', {
        description: '已同步删除服务器记录',
        action: {
          label: '撤销',
          onClick: () => void load(),
        },
      });
      if (id === sessionId) {
        resetChat();
      }
    } catch (e) {
      toast.error('删除失败', { description: (e as Error).message });
    }
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <button
        type="button"
        onClick={newChat}
        className="mx-2 mb-2 flex items-center gap-2 rounded-lg border border-border bg-background px-3 py-2 text-xs font-medium text-foreground transition-colors hover:bg-sidebar-accent"
      >
        <MessageSquarePlus className="h-4 w-4" />
        新建会话
      </button>

      <div className="min-h-0 flex-1 overflow-y-auto px-2">
        {history.length === 0 ? (
          <p className="px-3 py-2 text-[11px] text-muted-foreground">暂无历史会话</p>
        ) : (
          history.map((h) => {
            const active = h.sessionId === sessionId;
            const confirming = confirmingId === h.sessionId;
            return (
              <div
                key={h.sessionId}
                role="button"
                tabIndex={0}
                onClick={() => void switchSession(h.sessionId)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') void switchSession(h.sessionId);
                }}
                className={cn(
                  'group mb-0.5 flex cursor-pointer items-center gap-1 rounded-md px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-foreground',
                  active && 'bg-sidebar-accent text-foreground',
                )}
              >
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{h.title || '新对话'}</span>
                  {h.time != null && (
                    <span className="block text-[10px] text-muted-foreground/70">
                      {formatSessionTime(h.time)}
                    </span>
                  )}
                </span>
                {confirming ? (
                  <span className="flex shrink-0 items-center gap-1" onClick={(e) => e.stopPropagation()}>
                    <button
                      type="button"
                      className="rounded bg-destructive px-1.5 py-0.5 text-[10px] text-white hover:bg-destructive/90"
                      onClick={() => void doDelete(h.sessionId)}
                    >
                      确认
                    </button>
                    <button
                      type="button"
                      className="rounded p-0.5 text-muted-foreground hover:text-foreground"
                      onClick={() => setConfirmingId(null)}
                    >
                      <X className="h-3 w-3" />
                    </button>
                  </span>
                ) : (
                  <button
                    type="button"
                    aria-label={`删除会话 ${h.title || ''}`}
                    className="shrink-0 rounded p-1 text-muted-foreground/50 opacity-0 transition-opacity hover:bg-destructive/10 hover:text-destructive group-hover:opacity-100"
                    onClick={(e) => {
                      e.stopPropagation();
                      setConfirmingId(h.sessionId);
                    }}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
