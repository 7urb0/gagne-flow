import { useRef, useState } from 'react';
import { CheckCircle2, CloudUpload, FileText, Loader2, Trash2, XCircle } from 'lucide-react';
import { toast } from 'sonner';
import { uploadFiles } from '@/api/upload';
import { formatFileSize } from '@/lib/format';
import { cn } from '@/lib/utils';

const ALLOWED_EXTS = ['txt', 'md', 'pdf', 'docx'];
const ALLOWED_HINT = '支持 txt、md、pdf、docx';

interface UploadItem {
  id: string;
  name: string;
  size: number;
  status: 'uploading' | 'done' | 'error';
  error?: string;
}

/**
 * 参考资料上传区 (教案模式专属)
 * E3: 统一字节单位 + 已上传/上传中/失败状态图标
 */
export function UploadZone({
  onUploaded,
  disabled,
}: {
  onUploaded?: (names: string[]) => void;
  disabled?: boolean;
}) {
  const [items, setItems] = useState<UploadItem[]>([]);
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const updateItem = (id: string, patch: Partial<UploadItem>) => {
    setItems((prev) => prev.map((it) => (it.id === id ? { ...it, ...patch } : it)));
  };

  const addFiles = (files: FileList | File[]) => {
    const list = Array.from(files);
    if (list.length === 0) return;

    const valid: File[] = [];
    for (const f of list) {
      const ext = f.name.split('.').pop()?.toLowerCase() || '';
      if (!ALLOWED_EXTS.includes(ext)) {
        toast.error(`"${f.name}" 格式不支持`, { description: ALLOWED_HINT });
        continue;
      }
      if (items.some((it) => it.name === f.name)) continue;
      valid.push(f);
    }
    if (valid.length === 0) return;

    const newItems: UploadItem[] = valid.map((f) => ({
      id: `${f.name}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      name: f.name,
      size: f.size,
      status: 'uploading',
    }));
    setItems((prev) => [...prev, ...newItems]);

    void uploadFiles(valid).then(({ uploaded, errors }) => {
      const uploadedNames = new Set(uploaded.map((u) => u.fileName));
      for (const it of newItems) {
        if (uploadedNames.has(it.name)) {
          updateItem(it.id, { status: 'done' });
        } else {
          const err = errors.find((e) => e.startsWith(it.name));
          updateItem(it.id, { status: 'error', error: err || '上传失败' });
        }
      }
      if (errors.length > 0) {
        toast.warning('部分文件上传失败', { description: errors.join('；') });
      } else {
        toast.success('参考资料上传完成');
      }
      onUploaded?.(newItems.filter((it) => uploadedNames.has(it.name)).map((it) => it.name));
    });
  };

  const removeItem = (id: string) => {
    setItems((prev) => prev.filter((it) => it.id !== id));
    onUploaded?.([]); // 通知父组件重新读取已完成项
  };

  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        aria-label="上传参考资料（拖拽或点击）"
        className={cn(
          'flex cursor-pointer flex-col items-center gap-2 rounded-xl border-2 border-dashed border-border p-4 text-sm text-muted-foreground transition-colors',
          'hover:border-primary hover:bg-muted/40',
          dragging && 'border-primary bg-primary/5 text-primary',
          disabled && 'pointer-events-none opacity-50',
        )}
        onClick={() => inputRef.current?.click()}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click();
        }}
        onDragEnter={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragOver={(e) => e.preventDefault()}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          if (disabled) return;
          addFiles(e.dataTransfer.files);
        }}
      >
        <CloudUpload className="h-8 w-8 opacity-60" />
        <span>
          拖拽文件到此处或<span className="mx-1 font-semibold text-primary underline">点击上传</span>
        </span>
        <span className="text-xs opacity-60">{ALLOWED_HINT}</span>
        <input
          ref={inputRef}
          type="file"
          accept=".txt,.md,.pdf,.docx"
          multiple
          className="hidden"
          onChange={(e) => {
            if (e.target.files) addFiles(e.target.files);
            e.target.value = '';
          }}
        />
      </div>

      {items.length > 0 && (
        <ul className="mt-2 flex flex-col gap-1.5">
          {items.map((it) => (
            <li
              key={it.id}
              className={cn(
                'flex items-center gap-2 rounded-lg border px-3 py-2 text-xs',
                it.status === 'done' && 'border-primary/40 bg-primary/5',
                it.status === 'error' && 'border-red-200 bg-red-50',
                it.status === 'uploading' && 'border-border bg-muted/30',
              )}
            >
              <FileText
                className={cn(
                  'h-4 w-4 shrink-0',
                  it.name.endsWith('.pdf') && 'text-red-500',
                  it.name.endsWith('.docx') && 'text-blue-600',
                  (it.name.endsWith('.md') || it.name.endsWith('.txt')) && 'text-primary',
                )}
              />
              <span className="min-w-0 flex-1 truncate font-medium">{it.name}</span>
              <span className="shrink-0 text-muted-foreground">{formatFileSize(it.size)}</span>
              {it.status === 'uploading' && <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-primary" />}
              {it.status === 'done' && <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-600" />}
              {it.status === 'error' && (
                <span className="shrink-0 text-red-500" title={it.error}>
                  <XCircle className="h-3.5 w-3.5" />
                </span>
              )}
              <button
                type="button"
                aria-label={`移除 ${it.name}`}
                className="shrink-0 rounded p-1 text-muted-foreground hover:bg-border hover:text-foreground"
                onClick={() => removeItem(it.id)}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
