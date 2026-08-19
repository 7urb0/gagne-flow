import type { ParsedScore, ScoreDimensions } from '@/types';

/**
 * LLM 质量评估解析 (E1 容错):
 * 后端 SCORE_JSON 形如 {"score":X,"dimensions":{"clarity","accuracy","strategy","alignment","format"}}
 * 兼容空格/中文冒号/换行/键名大小写差异; 解析失败返回 null (由调用方给出明确提示, 不静默缺失)
 */

const DIM_KEYS: (keyof ScoreDimensions)[] = [
  'clarity',
  'accuracy',
  'strategy',
  'alignment',
  'format',
];

export const SCORE_LABELS: Record<keyof ScoreDimensions, string> = {
  clarity: '目标清晰度',
  accuracy: '内容准确性',
  strategy: '策略合理性',
  alignment: '课标对齐度',
  format: '格式规范度',
};

function extractNumber(text: string): number | null {
  const m = text.match(/-?\d+/);
  return m ? parseInt(m[0], 10) : null;
}

export function parseScore(reviewText: string): ParsedScore | null {
  if (!reviewText || !reviewText.trim()) return null;
  const text = reviewText.trim();

  // 总分: 多种写法兜底
  let total: number | null = null;
  const totalPatterns = [
    /"score"\s*[:：]\s*(\d+)/i,
    /总分\s*[:：]?\s*(\d+)/,
    /score\s*[:：]\s*(\d+)/i,
    /(\d+)\s*\/\s*100/i,
  ];
  for (const p of totalPatterns) {
    const m = text.match(p);
    if (m) {
      total = parseInt(m[1], 10);
      break;
    }
  }
  if (total == null) return null;
  total = Math.max(0, Math.min(100, total));

  const dims: ScoreDimensions = {} as ScoreDimensions;
  for (const key of DIM_KEYS) {
    const pattern = new RegExp(`["']?${key}["']?\\s*[:：]\\s*(\\d+)`, 'i');
    const m = text.match(pattern);
    if (m) {
      dims[key] = Math.max(0, Math.min(20, parseInt(m[1], 10)));
    }
  }

  // 部分维度缺失: 用总分均摊兜底 (与旧前端行为一致)
  for (const key of DIM_KEYS) {
    if (dims[key] == null) {
      dims[key] = Math.round(total / 5);
    }
  }

  return { total, dims };
}

/** 兜底: 从 review 文本里找 5 个维度数值的宽松方案 (兼容中文标签) */
export function parseScoreLenient(reviewText: string): ParsedScore | null {
  const standard = parseScore(reviewText);
  if (standard) return standard;

  // 中文标签行如 "目标清晰度: 15/20"
  const zhLabels: Record<string, keyof ScoreDimensions> = {
    目标清晰度: 'clarity',
    内容准确性: 'accuracy',
    策略合理性: 'strategy',
    课标对齐度: 'alignment',
    格式规范度: 'format',
  };
  const dims = {} as ScoreDimensions;
  for (const [zh, key] of Object.entries(zhLabels)) {
    const m = reviewText.match(new RegExp(`${zh}\\s*[:：]?\\s*(\\d+)\\s*/\\s*20`));
    if (m) dims[key] = Math.max(0, Math.min(20, parseInt(m[1], 10)));
  }
  const filled = DIM_KEYS.filter((k) => dims[k] != null);
  if (filled.length === 0) return null;
  for (const key of DIM_KEYS) {
    if (dims[key] == null) dims[key] = 0;
  }
  const total = filled.reduce((s, k) => s + dims[k], 0);
  return { total: Math.min(100, total), dims };
}
