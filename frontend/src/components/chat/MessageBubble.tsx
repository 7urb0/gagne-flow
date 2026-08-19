import { BookOpenText } from 'lucide-react';
import type { ChatMessage } from '@/types';
import { Markdown } from '@/components/chat/Markdown';
import { renderLessonPlanLinks } from '@/lib/pdfLink';
import { cn } from '@/lib/utils';

interface MessageBubbleProps {
  message: ChatMessage;
  streaming?: boolean;
  username?: string;
}

/** 聊天消息 (Vercel AI Chatbot 风格: 用户消息浅灰圆角块, 助手消息无气泡直接排版) */
export function MessageBubble({ message, streaming, username }: MessageBubbleProps) {
  const isUser = message.role === 'user';

  if (isUser) {
    return (
      <div className="message-animation flex justify-end">
        <div className="max-w-[85%] rounded-2xl bg-muted px-4 py-2.5 text-sm leading-relaxed text-foreground md:max-w-[70%]">
          <div className="whitespace-pre-wrap break-words">{message.content}</div>
        </div>
      </div>
    );
  }

  // 渲染前替换教案工具返回的 pdfUrl JSON 为下载链接
  const content = renderLessonPlanLinks(message.content);

  return (
    <div className="message-animation flex gap-3">
      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full border bg-background text-muted-foreground">
        <BookOpenText className="h-4 w-4" />
      </div>
      <div
        className={cn(
          'min-w-0 max-w-[92%] flex-1 pt-0.5 text-sm leading-relaxed md:max-w-[85%]',
          streaming && 'opacity-90',
        )}
        aria-live="polite"
      >
        {content ? (
          <Markdown content={content} />
        ) : (
          <span className="inline-flex items-center gap-1 text-muted-foreground">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-current" />
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-current [animation-delay:0.2s]" />
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-current [animation-delay:0.4s]" />
            正在思考...
          </span>
        )}
      </div>
    </div>
  );
}
