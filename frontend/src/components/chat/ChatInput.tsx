import { useRef, useEffect, useState } from 'react';
import { Square, ArrowUp } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

interface ChatInputProps {
  disabled?: boolean;
  streaming?: boolean;
  placeholder?: string;
  onSend: (text: string) => void;
  onStop?: () => void;
}

/** composer 风格输入框 (Vercel AI Chatbot 风格): 居中、圆角、悬浮阴影; Enter 发送 / Shift+Enter 换行 (E6) */
export function ChatInput({ disabled, streaming, placeholder, onSend, onStop }: ChatInputProps) {
  const [value, setValue] = useState('');
  const taRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 160) + 'px';
  }, [value]);

  const submit = () => {
    const text = value.trim();
    if (!text || disabled || streaming) return;
    setValue('');
    onSend(text);
  };

  return (
    <div className="px-4 pb-4 pt-2 md:px-6">
      <div className="composer mx-auto flex w-full max-w-3xl items-end gap-2 rounded-2xl border border-border bg-background px-3 py-2 transition-shadow focus-within:border-foreground/20">
        <textarea
          ref={taRef}
          value={value}
          rows={1}
          aria-label="消息输入框"
          placeholder={placeholder || '输入你的教学需求...'}
          className="max-h-[160px] flex-1 resize-none bg-transparent px-1 py-1.5 text-sm leading-relaxed outline-none placeholder:text-muted-foreground"
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
        />
        {streaming ? (
          <Button
            type="button"
            variant="outline"
            size="icon"
            aria-label="停止生成"
            onClick={onStop}
            className="shrink-0 rounded-full"
          >
            <Square className="h-4 w-4 fill-current" />
          </Button>
        ) : (
          <Button
            type="button"
            size="icon"
            aria-label="发送消息"
            disabled={disabled || !value.trim()}
            onClick={submit}
            className={cn('shrink-0 rounded-full transition-all', value.trim() && 'scale-100')}
          >
            <ArrowUp className="h-4 w-4" />
          </Button>
        )}
      </div>
      <p className="mt-1.5 text-center text-[11px] text-muted-foreground">
        Enter 发送 · Shift+Enter 换行
      </p>
    </div>
  );
}
