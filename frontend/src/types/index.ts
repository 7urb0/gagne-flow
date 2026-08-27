/**
 * 后端 DTO 契约类型 (与 src/main/java/com/gagneflow/dto/** 对齐)
 */

// ---------- 认证 ----------
export interface LoginResponse {
  token: string;
  refreshToken: string;
  username: string;
}

export interface AuthMessage {
  message?: string;
  username?: string;
  error?: string;
}

// ---------- 通用 ----------
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data?: T;
}

export interface ApiErrorBody {
  error?: string;
  message?: string;
}

// ---------- 会话 ----------
export interface ChatHistoryItem {
  sessionId: string;
  title?: string;
  time?: string | number;
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
}

export interface PoolStatus {
  activeThreads: number;
  poolSize: number;
  queueSize: number;
  completedTasks: number;
}

// ---------- 教案 ----------
export interface LessonPlanRequest {
  stage: '小学' | '初中' | '高中';
  grade: number; // 1-12
  subject: string;
  hours: number; // 1-20
  goals: string; // 2-500 字
  mode: 'quick' | 'copilot';
  uploadedFileNames?: string[];
  studentProfile?: string;
  keyPoints?: string;
  stylePreference?: string;
  assignmentRequirement?: string;
  specialRequirements?: string;
  Id: string;
  Question: string;
}

// ---------- 上传 ----------
export interface FileUploadRes {
  fileName: string;
  filePath: string;
  fileSize: number;
  vectorError?: string;
  indexStatus?: string; // indexing | done
}

export interface BatchUploadData {
  uploadedFiles: FileUploadRes[];
  errors: string[];
  totalCount: number;
  successCount: number;
  failCount: number;
}

// ---------- 管理后台 ----------
export interface PromptVersion {
  versionNumber: number;
  active: boolean;
  description: string;
  createdAt: string;
  contentLength: number;
}

export interface PromptActivateResult {
  promptName: string;
  versionNumber: number;
  active: boolean;
  message: string;
}

export interface PromptComparison {
  [key: string]: unknown;
}

export interface VersionStats {
  [key: string]: unknown;
}

export interface ExperimentStatus {
  enabled: boolean;
  splits: Record<string, unknown> | string;
}

// ---------- SSE 事件 (教案流水线) ----------
export interface StageContentEvent {
  type: 'stage:content';
  stage: string;
  chunk: string;
}

export interface StageCompleteEvent {
  type:
    | 'stage:analysis'
    | 'stage:design'
    | 'stage:development'
    | 'stage:review'
    | 'stage:format';
  stage: string;
  content?: string;
  updated?: boolean;
  /** B1 修复: 后端在 stage:format 事件中下发的真实教案 sessionId */
  sessionId?: string;
  /** 2026-08-21 Layer2: 后端在 stage:review 事件下发的结构化评分(单一数据源, 前端不再 re-parse) */
  score?: number;
  dimensions?: ScoreDimensions;
}

export interface AnalysisClarifyEvent {
  type: 'analysis_clarify';
  questions: string;
  token: string;
}

export interface StageAwaitEvent {
  type: 'stage_await';
  stage: string;
  token?: string;
  content?: string;
}

export interface SseMessageEvent {
  type: 'content' | 'error' | 'done';
  data?: string | null;
}

export type LessonSseEvent =
  | StageContentEvent
  | StageCompleteEvent
  | AnalysisClarifyEvent
  | StageAwaitEvent
  | SseMessageEvent;

// ---------- LLM 质量评估 ----------
export interface ScoreDimensions {
  clarity: number;
  accuracy: number;
  strategy: number;
  alignment: number;
  format: number;
}

export interface ParsedScore {
  total: number;
  dims: ScoreDimensions;
}
