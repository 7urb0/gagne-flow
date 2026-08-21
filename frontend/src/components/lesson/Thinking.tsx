import { forwardRef } from 'react';

interface ThinkingProps {
  /** 主文案 */
  label?: string;
  /** 次要说明 (如进度提示) */
  hint?: string;
  /** 尺寸 */
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

/**
 * 思考/生成中动画: 脉冲光点 + 跳动点, 用于教案生成过程的等待反馈。
 * - quick 模式: 整体"正在生成完整教案"占位
 * - copilot 模式: analysis 阶段"正在分析教学需求"思考过程占位
 */
export const Thinking = forwardRef<HTMLDivElement, ThinkingProps>(
  ({ label = 'AI 正在思考…', hint, size = 'md', className = '' }, ref) => {
    const dot = size === 'lg' ? 'h-3 w-3' : size === 'sm' ? 'h-1.5 w-1.5' : 'h-2 w-2';
    const ring = size === 'lg' ? 'h-16 w-16' : size === 'sm' ? 'h-8 w-8' : 'h-12 w-12';
    const text = size === 'lg' ? 'text-base' : size === 'sm' ? 'text-xs' : 'text-sm';
    return (
      <div
        ref={ref}
        className={`flex flex-col items-center justify-center gap-4 py-10 text-center ${className}`}
      >
        <div className="relative flex items-center justify-center">
          <span
            className={`${ring} animate-ping rounded-full bg-indigo-400/30`}
            aria-hidden
          />
          <span
            className={`absolute ${ring} rounded-full bg-indigo-500/10`}
            aria-hidden
          />
          <div className="flex items-center gap-1.5">
            <span className={`${dot} animate-bounce rounded-full bg-indigo-500 [animation-delay:-0.3s]`} />
            <span className={`${dot} animate-bounce rounded-full bg-indigo-500 [animation-delay:-0.15s]`} />
            <span className={`${dot} animate-bounce rounded-full bg-indigo-500`} />
          </div>
        </div>
        <div className={text + ' font-medium text-slate-700'}>{label}</div>
        {hint && <div className="text-xs text-slate-400">{hint}</div>}
      </div>
    );
  },
);

Thinking.displayName = 'Thinking';
