import { useMemo } from 'react';
import { AlertCircle, BarChart3 } from 'lucide-react';
import type { ParsedScore } from '@/types';
import { SCORE_LABELS } from '@/lib/score';
import { cn } from '@/lib/utils';

const DIMS = ['clarity', 'accuracy', 'strategy', 'alignment', 'format'] as const;

/** SVG 五维雷达图 */
function RadarChart({ score }: { score: ParsedScore }) {
  const size = 260;
  const cx = size / 2;
  const cy = size / 2;
  const r = size / 2 - 46;

  const points = (multiplier: number) =>
    DIMS.map((k, i) => {
      const angle = Math.PI / 2 + (2 * Math.PI * i) / DIMS.length;
      const rr = r * multiplier;
      return [cx + rr * Math.cos(angle), cy - rr * Math.sin(angle)] as const;
    });

  const dataPoints = DIMS.map((k, i) => {
    const angle = Math.PI / 2 + (2 * Math.PI * i) / DIMS.length;
    const val = Math.max(0, Math.min(1, score.dims[k] / 20));
    const rr = r * val;
    return [cx + rr * Math.cos(angle), cy - rr * Math.sin(angle)] as const;
  });

  const polyPoints = dataPoints.map((p) => p.join(',')).join(' ');
  const labelPos = DIMS.map((k, i) => {
    const angle = Math.PI / 2 + (2 * Math.PI * i) / DIMS.length;
    return [cx + (r + 26) * Math.cos(angle), cy - (r + 26) * Math.sin(angle)] as const;
  });

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="mx-auto block" role="img" aria-label="质量评估雷达图">
      {[1, 2, 3, 4].map((level) => {
        const pts = points(level / 4).map((p) => p.join(',')).join(' ');
        return (
          <polygon
            key={level}
            points={pts}
            fill="none"
            stroke={level === 4 ? 'hsl(240 5.9% 10%)' : '#e4e4e7'}
            strokeWidth={level === 4 ? 1.5 : 0.5}
          />
        );
      })}
      {DIMS.map((k, i) => {
        const [x, y] = points(1)[i];
        return <line key={k} x1={cx} y1={cy} x2={x} y2={y} stroke="#d4d4d8" strokeWidth={0.5} />;
      })}
      <polygon points={polyPoints} fill="rgba(24,24,27,0.12)" stroke="hsl(240 5.9% 10%)" strokeWidth={2} />
      {dataPoints.map(([x, y], i) => (
        <circle key={i} cx={x} cy={y} r={3.5} fill="hsl(240 5.9% 10%)" />
      ))}
      {labelPos.map(([x, y], i) => (
        <text
          key={i}
          x={x}
          y={y + 4}
          textAnchor="middle"
          fontSize={11}
          fill="#5c534d"
        >
          {SCORE_LABELS[DIMS[i]]}
        </text>
      ))}
    </svg>
  );
}

/**
 * LLM 质量评估区块 (E1: 独立区块; E2: 维度标签不折行、数值右对齐等宽)
 * 解析失败给明确提示, 不静默缺失
 */
export function QualityScore({ score, raw }: { score: ParsedScore | null; raw?: string }) {
  const rows = useMemo(() => {
    if (!score) return [];
    return DIMS.map((k) => ({
      label: SCORE_LABELS[k],
      value: score.dims[k],
      pct: Math.round((score.dims[k] / 20) * 100),
    }));
  }, [score]);

  if (!score) {
    return (
      <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 p-4">
        <div className="flex items-center gap-2 text-sm font-semibold text-amber-800">
          <AlertCircle className="h-4 w-4" />
          质量评估暂不可用
        </div>
        <p className="mt-1 text-xs text-amber-700">
          后端返回的评估内容未能解析出评分数据（原始内容已附后，可人工查看）。
        </p>
        {raw && (
          <details className="mt-2">
            <summary className="cursor-pointer text-xs text-amber-700 underline">
              查看原始评估内容
            </summary>
            <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap rounded bg-white/60 p-2 text-[11px] text-amber-900">
              {raw}
            </pre>
          </details>
        )}
      </div>
    );
  }

  return (
    <div className="mb-4 rounded-xl border border-primary/30 bg-primary/5 p-4">
      <div className="flex items-center gap-2 text-sm font-bold">
        <BarChart3 className="h-4 w-4 text-primary" />
        质量评估
        <span className="ml-auto rounded-full bg-primary px-2.5 py-0.5 text-xs font-semibold text-white">
          {score.total}/100
        </span>
      </div>
      <div className="mt-3 grid gap-4 md:grid-cols-[240px_1fr]">
        <RadarChart score={score} />
        <div className="flex flex-col justify-center gap-2">
          {rows.map((row) => (
            <div key={row.label} className="flex items-center gap-3">
              <span className="w-16 shrink-0 text-xs text-muted-foreground">{row.label}</span>
              <div className="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-border">
                <div
                  className="h-full rounded-full bg-primary transition-all duration-500"
                  style={{ width: `${row.pct}%` }}
                />
              </div>
              <span className="w-20 shrink-0 text-right font-mono text-xs font-semibold tabular-nums">
                {row.value}/20 · {row.pct}%
              </span>
            </div>
          ))}
          <p className="mt-1 text-[11px] text-muted-foreground">
            维度满分 20 分，总分 100 分
          </p>
        </div>
      </div>
    </div>
  );
}
