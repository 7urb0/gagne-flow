import { apiFetch } from '@/lib/api';
import type { ApiResponse, BatchUploadData, FileUploadRes } from '@/types';

/**
 * 参考资料上传 (教案模式专属)
 * POST /api/upload, multipart/form-data, 字段名 file, 支持多文件
 */
export async function uploadFiles(files: File[], onProgress?: (done: number, total: number) => void): Promise<{
  uploaded: FileUploadRes[];
  errors: string[];
}> {
  const uploaded: FileUploadRes[] = [];
  const errors: string[] = [];
  let done = 0;

  // 逐文件上传 (后端按 file 字段收集 List<MultipartFile>, 一次传一个最稳)
  for (const file of files) {
    const fd = new FormData();
    fd.append('file', file);
    try {
      const res = await apiFetch<ApiResponse<BatchUploadData>>('/api/upload', {
        method: 'POST',
        body: fd,
        timeoutMs: 120000,
      });
      if (res.code === 200 || res.code === 207) {
        if (res.data?.uploadedFiles) uploaded.push(...res.data.uploadedFiles);
        if (res.data?.errors) errors.push(...res.data.errors);
      } else {
        errors.push(`${file.name}: ${res.message || '上传失败'}`);
      }
    } catch (e) {
      errors.push(`${file.name}: ${(e as Error).message || '上传失败'}`);
    }
    done += 1;
    onProgress?.(done, files.length);
  }
  return { uploaded, errors };
}
