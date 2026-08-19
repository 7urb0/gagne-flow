import { useEffect, useState } from 'react';
import { Activity, Loader2 } from 'lucide-react';
import { fetchPoolStatus } from '@/api/chat';
import type { PoolStatus } from '@/types';
import { cn } from '@/lib/utils';

/** 系统状态浮标: /api/chat/pool-status (可选功能, permitAll) */
export function SystemStatus() {
  const [status, setStatus] = useState<PoolStatus | null>(null);
  const [ok, setOk] = useState<boolean | null>(null);

  useEffect(() => {
    let alive = true;
    const tick = () => {
      void fetchPoolStatus().then((s) => {
        if (!alive) return;
        setStatus(s);
        setOk(s != null);
      });
    };
    tick();
    const timer = setInterval(tick, 15000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, []);

  return (
    <div
      className="flex items-center gap-1.5 rounded-full px-1 py-0.5 text-[11px] text-muted-foreground"
      title={
        status
          ? `线程池: 活跃 ${status.activeThreads}/${status.poolSize} · 队列 ${status.queueSize} · 完成 ${status.completedTasks}`
          : '后端服务状态'
      }
    >
      {ok == null ? (
        <Loader2 className="h-3 w-3 animate-spin" />
      ) : ok ? (
        <Activity className={cn('h-3 w-3 text-emerald-600')} />
      ) : (
        <Activity className="h-3 w-3 text-destructive" />
      )}
      <span className="hidden md:inline">{ok ? '服务正常' : '后端离线'}</span>
    </div>
  );
}
