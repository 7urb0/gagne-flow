import { create } from 'zustand';

export type StageName = 'analysis' | 'design' | 'development' | 'review' | 'format';

export const STAGE_ORDER: StageName[] = ['analysis', 'design', 'development', 'review', 'format'];

export const STAGE_LABELS: Record<StageName, string> = {
  analysis: '教学分析',
  design: '教学设计',
  development: '教学过程',
  review: '质量评估',
  format: '排版渲染',
};

export type StageStatus = 'pending' | 'running' | 'done' | 'error';

/** 一份已完成的教案结果 (L4: 多份保留) */
export interface LessonResultItem {
  /** 后端真实 sid (B1, 来自 stage:format 事件 sessionId) */
  sessionId: string;
  /** 完整教案 HTML (Review 完成后由 updated 事件刷新的最终版) */
  html: string;
  /** 生成的展示标题 */
  title: string;
  createdAt: number;
  /** 2026-08-21 Layer2: LLM 结构化评分(来自 stage:review 事件, 单一数据源) */
  llmScore?: number;
  /** 2026-08-21 Layer2: LLM 五维评分(后端解析, 前端直接消费, 不 re-parse review 文本) */
  reviewDimensions?: import('@/types').ScoreDimensions;
  reviewText?: string;
  /** 是否需要人工复核(后端 HITL 红字警告已注入 html) */
  needsHumanReview?: boolean;
}

interface LessonState {
  generating: boolean;
  /** 当前各阶段状态 (进度弹窗) */
  stages: Record<StageName, StageStatus>;
  currentStage: StageName | null;
  /** 生成开始时间 (计时) */
  startedAt: number | null;
  stageStartedAt: Record<StageName, number>;
  /** 后端真实教案 sid, 从 stage:format 事件捕获 */
  activeSessionId: string | null;
  /** 教案结果历史 (L4) —— 重新生成时追加, 不清空 */
  results: LessonResultItem[];
  /** 当前展示的结果索引 */
  activeResultIndex: number;
  /** 用户评分状态 */
  scoreSubmitted: boolean;
  lastError: string | null;
  /** 本次生成的教学模式: quick=快速生成, copilot=分步确认 */
  mode: 'quick' | 'copilot';

  /**
   * 重新生成闸门:
   * 若新教案生成时用户仍在处理旧教案(未完成/未评分), 新教案暂存此索引,
   * 不直接抢占展示; 等用户确认"处理完毕"后由 showNewPlan 取出。
   */
  pendingNewIndex: number | null;
  /** 已确认"处理完毕"的教案 sid 集合 (评分或显式确认) */
  completedSids: Record<string, boolean>;
  /** HITL 人工复核处, 用户已决策"保留"的 sid 集合 */
  keepDecided: Record<string, boolean>;

  reset: () => void;
  setGenerating: (v: boolean, mode?: 'quick' | 'copilot') => void;
  setStageStatus: (stage: StageName, status: StageStatus) => void;
  setCurrentStage: (s: StageName | null) => void;
  setActiveSessionId: (id: string | null) => void;
  addResult: (r: LessonResultItem) => void;
  updateResultHtml: (sessionId: string, html: string) => void;
  /** P1-1: 更新已存在结果的评估数据(stage:review 晚于 addResult 到达, 需原位回填 llmScore/dimensions/reviewText) */
  updateResultAssessment: (
    sessionId: string,
    a: { llmScore?: number; reviewDimensions?: import('@/types').ScoreDimensions; reviewText?: string },
  ) => void;
  setActiveResultIndex: (i: number) => void;
  setScoreSubmitted: (v: boolean) => void;
  setLastError: (msg: string | null) => void;
  setPendingNew: (i: number | null) => void;
  markCompleted: (sid: string) => void;
  setKeepDecided: (sid: string, v: boolean) => void;
  removeResult: (sid: string) => void;
}

const initialStages = (): Record<StageName, StageStatus> => ({
  analysis: 'pending',
  design: 'pending',
  development: 'pending',
  review: 'pending',
  format: 'pending',
});

export const useLessonStore = create<LessonState>((set) => ({
  generating: false,
  stages: initialStages(),
  currentStage: null,
  startedAt: null,
  stageStartedAt: {} as Record<StageName, number>,
  activeSessionId: null,
  results: [],
  activeResultIndex: 0,
  scoreSubmitted: false,
  lastError: null,
  mode: 'quick',
  pendingNewIndex: null,
  completedSids: {},
  keepDecided: {},

  // 注意: reset 仅清空"本次生成运行态", 保留 results / completedSids / keepDecided,
  // 以保证重新生成不会抹掉旧教案(L4 多份保留被误删)与用户的在办状态。
  reset: () =>
    set({
      generating: false,
      stages: initialStages(),
      currentStage: null,
      startedAt: null,
      stageStartedAt: {} as Record<StageName, number>,
      activeSessionId: null,
      scoreSubmitted: false,
      lastError: null,
      mode: 'quick',
      pendingNewIndex: null,
    }),
  setGenerating: (v, mode) =>
    set((s) => ({
      generating: v,
      startedAt: v ? Date.now() : s.startedAt,
      mode: mode ?? s.mode,
    })),
  setStageStatus: (stage, status) =>
    set((s) => {
      const stageStartedAt = { ...s.stageStartedAt };
      if (status === 'running' && !stageStartedAt[stage]) {
        stageStartedAt[stage] = Date.now();
      }
      return { stages: { ...s.stages, [stage]: status }, stageStartedAt };
    }),
  setCurrentStage: (s) => set({ currentStage: s }),
  setActiveSessionId: (id) => set({ activeSessionId: id }),
  addResult: (r) =>
    set((s) => ({
      results: [...s.results, r],
      activeResultIndex: s.results.length,
    })),
  updateResultHtml: (sessionId, html) =>
    set((s) => ({
      results: s.results.map((r) =>
        r.sessionId === sessionId ? { ...r, html } : r,
      ),
    })),
  updateResultAssessment: (sessionId, a) =>
    set((s) => ({
      results: s.results.map((r) =>
        r.sessionId === sessionId
          ? {
              ...r,
              ...(a.llmScore != null ? { llmScore: a.llmScore } : {}),
              ...(a.reviewDimensions ? { reviewDimensions: a.reviewDimensions } : {}),
              ...(a.reviewText != null && a.reviewText !== '' ? { reviewText: a.reviewText } : {}),
            }
          : r,
      ),
    })),
  setActiveResultIndex: (i) => set({ activeResultIndex: i }),
  setScoreSubmitted: (v) => set({ scoreSubmitted: v }),
  setLastError: (msg) => set({ lastError: msg }),
  setPendingNew: (i) => set({ pendingNewIndex: i }),
  markCompleted: (sid) =>
    set((s) => ({ completedSids: { ...s.completedSids, [sid]: true } })),
  setKeepDecided: (sid, v) =>
    set((s) => ({ keepDecided: { ...s.keepDecided, [sid]: v } })),
  removeResult: (sid) =>
    set((s) => {
      const idx = s.results.findIndex((r) => r.sessionId === sid);
      if (idx < 0) return {};
      const results = s.results.filter((r) => r.sessionId !== sid);
      return {
        results,
        activeResultIndex: Math.max(0, Math.min(s.activeResultIndex, results.length - 1)),
      };
    }),
}));
