import { useState } from 'react';
import { CheckCircle2, Star } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { submitScore } from '@/api/lesson';
import { cn } from '@/lib/utils';

/**
 * 用户星级评分 (E1)
 * B1: 提交使用后端真实 sid (来自 SSE stage:format 的 sessionId), 成功明确提示
 */
export function ScorePanel({
  sessionId,
  onScored,
}: {
  sessionId: string | null;
  onScored?: () => void;
}) {
  const [selected, setSelected] = useState(0);
  const [hover, setHover] = useState(0);
  const [feedback, setFeedback] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);

  const doSubmit = async () => {
    if (!selected || submitting) return;
    if (!sessionId) {
      toast.error('评分失败', {
        description: '未获取到本次教案的会话标识，无法提交评分',
      });
      return;
    }
    setSubmitting(true);
    try {
      await submitScore(sessionId, selected, feedback.trim());
      setSubmitted(true);
      onScored?.();
      toast.success('已收到，感谢反馈', {
        description: `你的评分 ${selected} 星已提交`,
      });
    } catch (e) {
      toast.error('评分提交失败', {
        description: (e as Error).message,
      });
    } finally {
      setSubmitting(false);
    }
  };

  if (submitted) {
    return (
      <div className="mb-4 flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-800">
        <CheckCircle2 className="h-4 w-4" />
        <span>
          <strong>已收到，感谢反馈</strong>
          <span className="ml-2 text-emerald-600">（评分 {selected} 星）</span>
        </span>
      </div>
    );
  }

  return (
    <div className="mb-4 rounded-xl border border-dashed border-primary/40 bg-primary/5 p-4">
      <div className="text-sm font-semibold">这份教案符合你的预期吗？</div>
      <div className="mt-2 flex items-center gap-1" onMouseLeave={() => setHover(0)}>
        {[1, 2, 3, 4, 5].map((i) => (
          <button
            key={i}
            type="button"
            aria-label={`${i} 星`}
            className="p-0.5 transition-transform hover:scale-110"
            onMouseEnter={() => setHover(i)}
            onClick={() => setSelected(i)}
          >
            <Star
              className={cn(
                'h-6 w-6 transition-colors',
                (hover || selected) >= i ? 'fill-amber-400 text-amber-400' : 'text-muted-foreground/40',
              )}
            />
          </button>
        ))}
        {selected > 0 && (
          <span className="ml-2 text-xs text-muted-foreground">{selected} 星</span>
        )}
      </div>
      <div className="mt-3 flex items-center gap-2">
        <Input
          value={feedback}
          onChange={(e) => setFeedback(e.target.value)}
          placeholder="想改进哪里？（可选，最多 200 字）"
          maxLength={200}
          aria-label="评分反馈"
          className="h-8 flex-1 text-xs"
        />
        <Button
          size="sm"
          className="h-8"
          disabled={!selected || submitting}
          onClick={() => void doSubmit()}
        >
          {submitting ? '提交中...' : '提交评分'}
        </Button>
      </div>
    </div>
  );
}
