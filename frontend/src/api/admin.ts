import { apiFetch } from '@/lib/api';
import type {
  ExperimentStatus,
  PromptActivateResult,
  PromptComparison,
  PromptVersion,
  VersionStats,
} from '@/types';

export async function listPromptNames(): Promise<string[]> {
  return (await apiFetch<string[]>('/api/admin/prompts')) ?? [];
}

export async function listPromptVersions(name: string): Promise<PromptVersion[]> {
  return (
    (await apiFetch<PromptVersion[]>(
      `/api/admin/prompts/${encodeURIComponent(name)}`,
    )) ?? []
  );
}

export async function activatePrompt(name: string, version: number): Promise<PromptActivateResult> {
  return apiFetch<PromptActivateResult>(
    `/api/admin/prompts/${encodeURIComponent(name)}/${version}/activate`,
    { method: 'POST' },
  );
}

export async function comparePromptVersions(
  name: string,
  v1: number,
  v2: number,
): Promise<PromptComparison> {
  return apiFetch<PromptComparison>(
    `/api/admin/prompts/${encodeURIComponent(name)}/compare?v1=${v1}&v2=${v2}`,
  );
}

export async function fetchPromptStats(name: string): Promise<Record<string, VersionStats>> {
  return (
    (await apiFetch<Record<string, VersionStats>>(
      `/api/admin/prompts/${encodeURIComponent(name)}/stats`,
    )) ?? {}
  );
}

export async function fetchExperimentStatus(): Promise<ExperimentStatus | null> {
  try {
    return await apiFetch<ExperimentStatus>('/api/admin/prompts/experiment/status');
  } catch {
    return null;
  }
}
