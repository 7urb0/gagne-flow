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
  userScored: boolean;
  llmScore?: number;
  reviewText?: string;
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
  /** 教案结果历史 (L4) */
  results: LessonResultItem[];
  /** 当前展示的结果索引 */
  activeResultIndex: number;
  /** 用户评分状态 */
  scoreSubmitted: boolean;
  lastError: string | null;

  reset: () => void;
  setGenerating: (v: boolean) => void;
  setStageStatus: (stage: StageName, status: StageStatus) => void;
  setCurrentStage: (s: StageName | null) => void;
  setActiveSessionId: (id: string | null) => void;
  addResult: (r: LessonResultItem) => void;
  updateResultHtml: (sessionId: string, html: string) => void;
  setActiveResultIndex: (i: number) => void;
  setScoreSubmitted: (v: boolean) => void;
  setLastError: (msg: string | null) => void;
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
    }),
  setGenerating: (v) =>
    set((s) => ({
      generating: v,
      startedAt: v ? Date.now() : s.startedAt,
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
  setActiveResultIndex: (i) => set({ activeResultIndex: i }),
  setScoreSubmitted: (v) => set({ scoreSubmitted: v }),
  setLastError: (msg) => set({ lastError: msg }),
}));
