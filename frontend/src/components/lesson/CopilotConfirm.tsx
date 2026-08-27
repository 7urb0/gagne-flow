import { useEffect, useState } from 'react';
import { CheckCircle2, PencilLine, Square } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { submitCopilotAction } from '@/api/lesson';
import { STAGE_LABELS, type StageName } from '@/store/lesson';

/** 后端 emitCopilotAwait 的等待窗口(秒), 与 AddrfPipeline 对齐 */
const AWAIT_TIMEOUT_SEC = 120;

/**
 * Copilot 分步确认面板
 * 三按钮: 确认继续 / 修改后继续 / 停止生成
 * 提交 POST /api/lesson_plan/action {token, action, stage, instruction}
 * 2026-08-21: 增加超时倒计时提示 — 后端 120s 未收到操作会自动继续, 避免用户困惑"为什么自己走了"
 */
export function CopilotConfirm({
  stage,
  token,
  preview,
}: {
  stage: string;
  token: string;
  preview: string;
}) {
  const [state, setState] = useState<'idle' | 'input' | 'sent' | 'terminated'>('idle');
  const [instruction, setInstruction] = useState('');
  const [sending, setSending] = useState(false);
  const [remainSec, setRemainSec] = useState(AWAIT_TIMEOUT_SEC);

  useEffect(() => {
    if (state !== 'idle') return;
    const timer = window.setInterval(() => {
      setRemainSec((s) => Math.max(0, s - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [state]);

  const label = STAGE_LABELS[stage as StageName] || stage;

  const send = async (action: 'continue' | 'revise' | 'terminate', inst?: string) => {
    setSending(true);
    try {
      await submitCopilotAction({ token, action, stage, instruction: inst || '' });
      if (action === 'terminate') {
        setState('terminated');
        toast.info('已停止生成，可重新开始');
      } else {
        setState('sent');
      }
    } catch (e) {
      toast.error('操作提交失败', { description: (e as Error).message });
      setState('idle');
    } finally {
      setSending(false);
    }
  };

  if (state === 'terminated') {
    return (
      <div className="my-2 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        <strong>已停止生成</strong>，已生成的内容将保留
      </div>
    );
  }
  if (state === 'sent') {
    return (
      <div className="my-2 rounded-xl border border-primary/30 bg-primary/5 p-4 text-sm text-primary">
        <strong>{label}已确认</strong>，正在继续下一步...
      </div>
    );
  }

  return (
    <div className="my-2 rounded-xl border border-primary/30 bg-primary/5 p-4">
      <div className="mb-2 flex items-center gap-2 text-sm font-semibold">
        <CheckCircle2 className="h-4 w-4 text-primary" />
        {label}阶段已完成 — 请确认是否继续
      </div>
      <p className="mb-2 text-xs leading-relaxed text-muted-foreground">
        请确认本阶段内容；确认无误请点「确认继续」，需要调整请点「修改后继续」。等待您确认后再进入下一步。
      </p>
      {remainSec <= 30 ? (
        <p className="mb-2 text-xs font-semibold text-amber-700">
          若您在 {remainSec} 秒内未操作，将自动继续生成下一阶段…
        </p>
      ) : (
        <p className="mb-2 text-[11px] text-muted-foreground">
          倒计时 {remainSec}s 后未操作将自动继续（可随时点击按钮确认）
        </p>
      )}
      {preview && (
        <p className="mb-2 line-clamp-3 text-xs leading-relaxed text-muted-foreground">
          {preview}
        </p>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" disabled={sending} onClick={() => void send('continue')}>
          确认继续
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={sending}
          onClick={() => setState(state === 'input' ? 'idle' : 'input')}
        >
          <PencilLine className="h-3.5 w-3.5" />
          {state === 'input' ? '收起' : '修改后继续'}
        </Button>
        <Button
          size="sm"
          variant="outline"
          className="border-red-300 text-red-600 hover:bg-red-50 hover:text-red-700"
          disabled={sending}
          onClick={() => void send('terminate')}
        >
          <Square className="h-3.5 w-3.5" />
          停止生成
        </Button>
      </div>
      {state === 'input' && (
        <div className="mt-3">
          <Textarea
            value={instruction}
            onChange={(e) => setInstruction(e.target.value)}
            rows={2}
            placeholder="输入修改意见，然后点「提交修改」..."
            aria-label="修改意见"
            className="text-xs"
          />
          <Button
            size="sm"
            className="mt-2"
            disabled={sending || !instruction.trim()}
            onClick={() => void send('revise', instruction.trim())}
          >
            提交修改
          </Button>
        </div>
      )}
    </div>
  );
}
