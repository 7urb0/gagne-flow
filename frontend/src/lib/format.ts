/** 统一字节单位: >1MB 显 MB 保留 1 位, 否则 KB 保留 0 位 (E3) */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes >= 1024 * 1024) {
    return (bytes / (1024 * 1024)).toFixed(1) + 'MB';
  }
  if (bytes >= 1024) {
    return Math.round(bytes / 1024) + 'KB';
  }
  return bytes + 'B';
}

/** 阶段计时/耗时展示 mm:ss (E5) */
export function formatElapsed(ms: number): string {
  const totalSec = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

/** 会话列表时间展示 */
export function formatSessionTime(time: string | number | undefined): string {
  if (!time) return '';
  const ts = typeof time === 'number' ? time : Date.parse(time);
  if (!Number.isFinite(ts)) return '';
  const d = new Date(ts);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) {
    return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
  }
  return `${d.getMonth() + 1}-${d.getDate()}`;
}

/** 自然语言课时建议 (E3): "小学三年级数学 · 2课时" */
export function hoursSuggestion(stage: string, gradeLabel: string, subject: string, hours: number): string {
  return `${stage}${gradeLabel}${subject} · ${hours}课时`;
}
