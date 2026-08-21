import { useState } from 'react';
import { HelpCircle, Send } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { submitClarify } from '@/api/lesson';

/**
 * Analysis 意图澄清面板 (45s 窗口内提交有效)
 * POST /api/lesson_plan/clarify {token, answer}
 */
export function ClarifyPanel({ questions, token }: { questions: string; token: string }) {
  const [answer, setAnswer] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const questionLines = questions
    .split('\n')
    .map((l) => l.replace(/^[-*•]\s*/, '').trim())
    .filter(Boolean);

  const doSubmit = async () => {
    const text = answer.trim();
    if (!text || submitting) return;
    setSubmitting(true);
    try {
      await submitClarify(token, text);
      setSubmitted(true);
    } catch (e) {
      toast.error('回答提交失败（可能已超时）', { description: (e as Error).message });
      setSubmitted(true);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="my-2 rounded-xl border border-primary/30 bg-primary/5 p-4">
      <div className="flex items-center gap-2 text-sm font-semibold">
        <HelpCircle className="h-4 w-4 text-primary" />
        生成前小确认（可选）
        <span className="text-xs font-normal text-muted-foreground">45 秒内回答会融入生成</span>
      </div>
      <ul className="mt-2 flex flex-col gap-1.5">
        {questionLines.map((q, i) => (
          <li key={i} className="text-sm leading-relaxed">
            <span className="mr-1 font-semibold text-primary">{i + 1}.</span>
            {q}
          </li>
        ))}
      </ul>
      {submitted ? (
        <p className="mt-3 text-xs text-emerald-600">✓ 已收到你的回答，正在融入生成</p>
      ) : (
        <>
          <div className="mt-3 flex items-center gap-2">
            <Input
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              placeholder="快速回答（如：计算偏弱 / 讲练结合）"
              maxLength={300}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void doSubmit();
              }}
              className="h-8 flex-1 text-xs"
              aria-label="澄清问题回答"
            />
            <Button size="sm" className="h-8" disabled={!answer.trim() || submitting} onClick={() => void doSubmit()}>
              <Send className="h-3.5 w-3.5" />
              {submitting ? '提交中' : '提交'}
            </Button>
          </div>
          <p className="mt-1.5 text-[11px] text-muted-foreground">
            请确认您的回答，或选择跳过（45 秒内未回答将自动继续生成）
          </p>
        </>
      )}
    </div>
  );
}
