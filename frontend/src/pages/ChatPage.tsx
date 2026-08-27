import { useCallback, useEffect, useRef } from 'react';
import { toast } from 'sonner';
import { useChatStore } from '@/store/chat';
import { useAuthStore } from '@/store/auth';
import { useStreamChat } from '@/hooks/useStreamChat';
import { MessageBubble } from '@/components/chat/MessageBubble';
import { ChatInput } from '@/components/chat/ChatInput';

function Welcome() {
  return (
    <div className="flex h-full flex-col items-center justify-center text-center">
      <svg viewBox="0 0 64 64" className="mb-4 h-10 w-10 text-muted-foreground/60">
        <rect width="64" height="64" rx="14" fill="currentColor" opacity="0.12" />
        <path
          d="M32 14 L20 20 V44 L32 38 L44 44 V20 Z"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinejoin="round"
        />
        <line x1="32" y1="14" x2="32" y2="38" stroke="currentColor" strokeWidth="1.5" />
      </svg>
      <h2 className="text-2xl font-semibold tracking-tight">GagneFlow</h2>
      <p className="mt-2 text-sm text-muted-foreground">输入教学需求，生成教案与智能问答</p>
    </div>
  );
}

/** 智能对话页 /chat — 居中消息流 (Vercel AI Chatbot 风格) */
export function ChatPage() {
  const { messages, streaming } = useChatStore();
  const username = useAuthStore((s) => s.username);
  const scrollRef = useRef<HTMLDivElement>(null);

  const handleError = useCallback((msg: string) => {
    toast.error('发送失败', { description: msg });
  }, []);

  const { send, abort } = useStreamChat(handleError);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages]);

  const doSend = async (text: string) => {
    await send(text);
  };

  return (
    <div className="flex h-full flex-col">
      <header className="flex h-14 shrink-0 items-center border-b px-5 text-sm font-semibold">
        智能对话
      </header>

      <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto">
        {messages.length === 0 ? (
          <Welcome />
        ) : (
          <div className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-6 md:px-6">
            {messages.map((m, i) => (
              <MessageBubble
                key={i}
                message={m}
                streaming={streaming && i === messages.length - 1}
                username={username || undefined}
              />
            ))}
          </div>
        )}
      </div>

      <ChatInput
        streaming={streaming}
        disabled={streaming}
        onSend={(text) => void doSend(text)}
        onStop={abort}
        placeholder="输入你的教学需求..."
      />
    </div>
  );
}
