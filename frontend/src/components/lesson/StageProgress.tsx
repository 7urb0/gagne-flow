import { useEffect, useState } from 'react';
import { AlertTriangle, Check, CircleDashed, Loader2, X } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import {
  STAGE_LABELS,
  STAGE_ORDER,
  useLessonStore,
  type StageName,
  type StageStatus,
} from '@/store/lesson';
import { formatElapsed } from '@/lib/format';
import { cn } from '@/lib/utils';

function StageIcon({ status }: { status: StageStatus }) {
  if (status === 'done') return <Check className="h-4 w-4 text-emerald-600" />;
  if (status === 'error') return <X className="h-4 w-4 text-red-600" />;
  if (status === 'running') return <Loader2 className="h-4 w-4 animate-spin text-primary" />;
  return <CircleDashed className="h-4 w-4 text-muted-foreground/50" />;
}

function statusText(status: StageStatus, stage: StageName, elapsedMs?: number): string {
  const time = elapsedMs != null ? ` · ${formatElapsed(elapsedMs)}` : '';
  switch (status) {
    case 'running':
      // E4: Review 是后台异步, 显示"后台评估中"
      return stage === 'review' ? `后台评估中${time}` : `处理中...${time}`;
    case 'done':
      return '✓ 完成';
    case 'error':
      return '失败';
    default:
      return '等待中';
  }
}

/**
 * 五阶段进度弹窗 (E4)
 * - 严格按真实事件推进: 只有收到 stage:xxx 才标完成, Review 在 stage:review 前保持"后台评估中"
 * - 关闭(保留后台继续) ≠ 取消(真 abort, 二次确认)
 * - z 层级: 弹窗 900 (toast 9999 > 弹窗 900 > 工作台 500)
 */
export function StageProgress({
  onViewResult,
  onAbort,
  viewable,
}: {
  onViewResult: () => void;
  onAbort: () => void;
  viewable: boolean;
}) {
  const { generating, stages, currentStage, startedAt, stageStartedAt } = useLessonStore();
  const [confirmAbort, setConfirmAbort] = useState(false);
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (!generating || !startedAt) return;
    const timer = setInterval(() => setElapsed(Date.now() - startedAt), 1000);
    return () => clearInterval(timer);
  }, [generating, startedAt]);

  // 生成结束 3 秒后自动关闭确认 (若未手动关闭)
  useEffect(() => {
    if (!generating) {
      const t = setTimeout(() => setConfirmAbort(false), 3000);
      return () => clearTimeout(t);
    }
  }, [generating]);

  const allDone = STAGE_ORDER.every((s) => stages[s] === 'done');

  return (
    <Dialog open={generating || allDone} onOpenChange={() => undefined}>
      <DialogContent className="z-[900] max-w-[420px]" hideClose>
        <DialogHeader className="flex-row items-center gap-3 space-y-0 border-b pb-3">
          <DialogTitle className="text-base">教案生成中</DialogTitle>
          <DialogDescription className="sr-only">教案生成进度</DialogDescription>
          {generating && (
            <span className="ml-auto text-xs tabular-nums text-muted-foreground">
              已用时 {formatElapsed(elapsed)}
            </span>
          )}
          {!confirmAbort && generating && (
            <button
              type="button"
              aria-label="关闭弹窗（后台继续生成）"
              className="rounded-full bg-muted p-1.5 text-muted-foreground hover:bg-border hover:text-foreground"
              onClick={() => setConfirmAbort(false)}
              title="关闭弹窗，后台继续生成"
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </DialogHeader>

        <div className="flex flex-col gap-2 py-2">
          {STAGE_ORDER.map((stage) => {
            const status = stages[stage];
            const active = currentStage === stage;
            return (
              <div
                key={stage}
                className={cn(
                  'flex items-center gap-3 rounded-lg px-3 py-2 transition-colors',
                  active && 'bg-primary/10',
                  status === 'done' && 'opacity-70',
                )}
              >
                <StageIcon status={status} />
                <span
                  className={cn(
                    'flex-1 text-sm',
                    active ? 'font-semibold text-foreground' : 'text-foreground/80',
                  )}
                >
                  {STAGE_LABELS[stage]}
                </span>
                <span
                  className={cn(
                    'text-xs whitespace-nowrap',
                    status === 'running' && 'font-semibold text-primary',
                    status === 'done' && 'text-emerald-600',
                    status === 'error' && 'text-red-600',
                    status === 'pending' && 'text-muted-foreground',
                  )}
                >
                  {statusText(status, stage, stageStartedAt[stage] ? Date.now() - stageStartedAt[stage] : undefined)}
                </span>
              </div>
            );
          })}
        </div>

        <div className="flex justify-end gap-2 border-t pt-3">
          {generating && (
            <>
              {!confirmAbort ? (
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-muted-foreground"
                  onClick={() => setConfirmAbort(true)}
                >
                  取消生成
                </Button>
              ) : (
                <>
                  <span className="mr-1 flex items-center gap-1 text-xs text-red-600">
                    <AlertTriangle className="h-3.5 w-3.5" />
                    确定取消？已生成内容将保留
                  </span>
                  <Button
                    variant="destructive"
                    size="sm"
                    onClick={() => {
                      setConfirmAbort(false);
                      onAbort();
                    }}
                  >
                    确认取消
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => setConfirmAbort(false)}>
                    继续生成
                  </Button>
                </>
              )}
            </>
          )}
          {!generating && (
            <Button size="sm" onClick={onViewResult} disabled={!viewable}>
              查看教案
            </Button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
