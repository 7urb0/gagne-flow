import { useCallback, useEffect, useState } from 'react';
import { CheckCircle2, FlaskConical, GitCompareArrows, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  activatePrompt,
  comparePromptVersions,
  fetchExperimentStatus,
  fetchPromptStats,
  listPromptNames,
  listPromptVersions,
} from '@/api/admin';
import type { ExperimentStatus, PromptVersion } from '@/types';
import { cn } from '@/lib/utils';

function formatCompareValue(value: unknown): string {
  if (value == null) return '-';
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
}

/** 管理后台: prompt 版本列表 / 激活 / 对比 / 统计 (新补齐功能) */
export function AdminPromptsPage() {
  const [names, setNames] = useState<string[]>([]);
  const [selectedName, setSelectedName] = useState<string>('');
  const [versions, setVersions] = useState<PromptVersion[]>([]);
  const [experiment, setExperiment] = useState<ExperimentStatus | null>(null);
  const [compareOpen, setCompareOpen] = useState(false);
  const [v1, setV1] = useState<string>('');
  const [v2, setV2] = useState<string>('');
  const [compareData, setCompareData] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(false);

  const refreshNames = useCallback(async () => {
    try {
      const list = await listPromptNames();
      setNames(list);
      if (!selectedName && list.length > 0) setSelectedName(list[0]);
    } catch (e) {
      toast.error('加载提示词列表失败', { description: (e as Error).message });
    }
  }, [selectedName]);

  const refreshVersions = useCallback(
    async (name: string) => {
      if (!name) return;
      setLoading(true);
      try {
        const list = await listPromptVersions(name);
        setVersions(list);
      } catch (e) {
        toast.error('加载版本失败', { description: (e as Error).message });
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    void refreshNames();
    void fetchExperimentStatus().then(setExperiment).catch(() => undefined);
  }, [refreshNames]);

  useEffect(() => {
    if (selectedName) void refreshVersions(selectedName);
  }, [selectedName, refreshVersions]);

  const doActivate = async (version: number) => {
    try {
      const res = await activatePrompt(selectedName, version);
      toast.success(res.message || `Prompt v${version} 已激活`);
      void refreshVersions(selectedName);
    } catch (e) {
      toast.error('激活失败', { description: (e as Error).message });
    }
  };

  const doCompare = async () => {
    if (!v1 || !v2 || v1 === v2) {
      toast.warning('请选择两个不同的版本进行对比');
      return;
    }
    try {
      const data = await comparePromptVersions(selectedName, parseInt(v1, 10), parseInt(v2, 10));
      setCompareData(data);
      setCompareOpen(true);
    } catch (e) {
      toast.error('对比失败', { description: (e as Error).message });
    }
  };

  return (
    <div className="h-full overflow-y-auto p-5 md:p-6">
      <div className="mx-auto max-w-4xl">
        <div className="mb-4 flex items-center gap-2">
          <GitCompareArrows className="h-5 w-5 text-primary" />
          <h1 className="text-lg font-bold">提示词管理</h1>
          {experiment && (
            <Badge variant={experiment.enabled ? 'default' : 'secondary'} className="ml-auto">
              <FlaskConical className="mr-1 h-3 w-3" />
              实验分流 {experiment.enabled ? '已开启' : '已关闭'}
            </Badge>
          )}
        </div>

        <Card className="mb-4">
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-sm">
              选择提示词
              <Button variant="ghost" size="sm" className="ml-auto h-7 px-2" onClick={() => void refreshNames()}>
                <RefreshCw className="h-3.5 w-3.5" />
                刷新
              </Button>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <Select value={selectedName || undefined} onValueChange={setSelectedName}>
              <SelectTrigger>
                <SelectValue placeholder="请选择提示词" />
              </SelectTrigger>
              <SelectContent>
                {names.map((n) => (
                  <SelectItem key={n} value={n}>
                    {n}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-sm">
              版本列表
              {loading && <RefreshCw className="h-3.5 w-3.5 animate-spin" />}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {versions.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground">
                {selectedName ? '该提示词暂无版本' : '请先选择提示词'}
              </p>
            ) : (
              <div className="flex flex-col gap-2">
                {versions.map((v) => (
                  <div
                    key={v.versionNumber}
                    className={cn(
                      'flex items-center gap-3 rounded-lg border px-4 py-3',
                      v.active ? 'border-primary/50 bg-primary/5' : 'border-border',
                    )}
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 text-sm font-semibold">
                        v{v.versionNumber}
                        {v.active && (
                          <Badge variant="success">
                            <CheckCircle2 className="mr-1 h-3 w-3" />
                            已激活
                          </Badge>
                        )}
                      </div>
                      <div className="mt-0.5 truncate text-xs text-muted-foreground">
                        {v.description || '(无描述)'}
                      </div>
                      <div className="text-[11px] text-muted-foreground/70">
                        {new Date(v.createdAt).toLocaleString('zh-CN')} · 内容 {v.contentLength} 字符
                      </div>
                    </div>
                    <Button
                      size="sm"
                      variant={v.active ? 'secondary' : 'outline'}
                      disabled={v.active}
                      onClick={() => void doActivate(v.versionNumber)}
                    >
                      {v.active ? '当前版本' : '激活'}
                    </Button>
                  </div>
                ))}
              </div>
            )}

            {versions.length >= 2 && (
              <div className="mt-4 flex flex-wrap items-center gap-2 border-t pt-4">
                <span className="text-xs text-muted-foreground">版本对比:</span>
                <Select value={v1 || undefined} onValueChange={setV1}>
                  <SelectTrigger className="w-28">
                    <SelectValue placeholder="v1" />
                  </SelectTrigger>
                  <SelectContent>
                    {versions.map((v) => (
                      <SelectItem key={v.versionNumber} value={String(v.versionNumber)}>
                        v{v.versionNumber}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <span className="text-muted-foreground">vs</span>
                <Select value={v2 || undefined} onValueChange={setV2}>
                  <SelectTrigger className="w-28">
                    <SelectValue placeholder="v2" />
                  </SelectTrigger>
                  <SelectContent>
                    {versions.map((v) => (
                      <SelectItem key={v.versionNumber} value={String(v.versionNumber)}>
                        v{v.versionNumber}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button size="sm" variant="outline" onClick={() => void doCompare()}>
                  对比
                </Button>
              </div>
            )}
          </CardContent>
        </Card>

        <Dialog open={compareOpen} onOpenChange={setCompareOpen}>
          <DialogContent className="max-w-2xl">
            <DialogHeader>
              <DialogTitle className="text-base">
                版本对比: {selectedName} v{v1} vs v{v2}
              </DialogTitle>
            </DialogHeader>
            <div className="max-h-[60vh] overflow-auto rounded-lg border bg-muted/30 p-3">
              {compareData ? (
                <table className="w-full text-xs">
                  <tbody>
                    {Object.entries(compareData).map(([k, value]) => (
                      <tr key={k} className="border-b border-border last:border-0">
                        <td className="w-40 shrink-0 py-1.5 pr-3 align-top font-semibold text-muted-foreground">
                          {k}
                        </td>
                        <td className="py-1.5 align-top font-mono whitespace-pre-wrap break-all">
                          {formatCompareValue(value)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p className="py-4 text-center text-sm text-muted-foreground">暂无对比数据</p>
              )}
            </div>
          </DialogContent>
        </Dialog>
      </div>
    </div>
  );
}
