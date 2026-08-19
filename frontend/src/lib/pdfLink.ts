/**
 * 识别 agent 回答中的教案工具返回 (LessonPlanTools.getLatestLessonPlan):
 * {"status":"ok","sessionId":"...","pdfUrl":"/api/lesson_plan/pdf/xxx","preview":"..."}
 * 渲染为可点击下载链接
 */

interface LessonPlanRef {
  status: string;
  sessionId?: string;
  pdfUrl?: string;
  preview?: string;
  message?: string;
}

const JSON_BLOCK = /\{[^{}]*"status"\s*:\s*"(?:ok|no_lesson_plan|error)"[^{}]*\}/g;

export function extractLessonPlanRef(text: string): LessonPlanRef | null {
  const m = text.match(JSON_BLOCK);
  if (!m) return null;
  for (const raw of m) {
    try {
      const obj = JSON.parse(raw) as LessonPlanRef;
      if (obj.status === 'ok' && obj.pdfUrl) return obj;
    } catch {
      /* continue */
    }
  }
  return null;
}

/** 把工具返回 JSON 替换为 markdown 下载链接 (仅 ok 状态) */
export function renderLessonPlanLinks(text: string): string {
  if (!text) return text;
  let out = text;
  out = out.replace(JSON_BLOCK, (raw) => {
    try {
      const obj = JSON.parse(raw) as LessonPlanRef;
      if (obj.status === 'ok' && obj.pdfUrl) {
        return `[下载教案 PDF](${obj.pdfUrl})`;
      }
      if (obj.status === 'no_lesson_plan') {
        return `*(未找到最近生成的教案)*`;
      }
      return `*(教案获取失败: ${obj.message || '未知错误'})*`;
    } catch {
      return raw;
    }
  });
  return out;
}
