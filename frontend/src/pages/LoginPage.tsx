import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { loginApi, registerApi } from '@/api/auth';
import { authStorage } from '@/lib/api';
import { cn } from '@/lib/utils';

function Field({
  label,
  type,
  value,
  onChange,
  placeholder,
  minLength,
  autoComplete,
}: {
  label: string;
  type: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  minLength?: number;
  autoComplete?: string;
}) {
  return (
    <div className="w-full max-w-[320px]">
      <Label className="mb-1.5 block text-xs text-muted-foreground">{label}</Label>
      <Input
        type={type}
        value={value}
        minLength={minLength}
        autoComplete={autoComplete}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        className="h-11 rounded-xl border-border bg-background/80 px-4"
      />
    </div>
  );
}

/** 登录/注册切换卡片 — 中性配色 (深灰背景 + 白色卡片) */
export function LoginPage() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (!username.trim() || !password) {
      toast.warning('请填写用户名和密码');
      return;
    }
    if (mode === 'register' && password.length < 6) {
      toast.warning('密码至少需要 6 位');
      return;
    }
    setBusy(true);
    try {
      if (mode === 'login') {
        const data = await loginApi(username.trim(), password);
        authStorage.save(data.token, data.refreshToken, data.username);
        toast.success('登录成功');
        navigate('/chat', { replace: true });
      } else {
        await registerApi(username.trim(), password);
        toast.success('注册成功，请登录');
        setMode('login');
      }
    } catch (err) {
      toast.error(mode === 'login' ? '登录失败' : '注册失败', {
        description: (err as Error).message,
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex h-full items-center justify-center bg-gradient-to-br from-zinc-900 to-zinc-700 p-4">
      <div className="relative flex w-full max-w-[800px] overflow-hidden rounded-2xl bg-card shadow-2xl">
        {/* 左侧装饰面板 */}
        <div
          className={cn(
            'relative z-10 flex w-1/2 flex-col items-center justify-center bg-zinc-900 p-8 text-center text-white transition-all duration-500',
            mode === 'register' && 'order-2',
          )}
        >
          <svg viewBox="0 0 64 64" className="mb-3 h-10 w-10 text-white/80">
            <rect width="64" height="64" rx="14" fill="currentColor" opacity="0.15" />
            <path
              d="M32 14 L20 20 V44 L32 38 L44 44 V20 Z"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinejoin="round"
            />
            <line x1="32" y1="14" x2="32" y2="38" stroke="currentColor" strokeWidth="1.5" />
          </svg>
          <h3 className="text-lg font-semibold">GagneFlow</h3>
          {mode === 'login' ? (
            <>
              <p className="mt-2 text-sm opacity-80">首次使用？创建账号，开始智能教案之旅</p>
              <Button
                type="button"
                variant="outline"
                className="mt-6 border-white/30 bg-transparent text-white hover:bg-white/10"
                onClick={() => setMode('register')}
              >
                注册
              </Button>
            </>
          ) : (
            <>
              <p className="mt-2 text-sm opacity-80">已有账号？登录继续你的教学对话</p>
              <Button
                type="button"
                variant="outline"
                className="mt-6 border-white/30 bg-transparent text-white hover:bg-white/10"
                onClick={() => setMode('login')}
              >
                登录
              </Button>
            </>
          )}
        </div>

        {/* 右侧表单 */}
        <div className="flex w-1/2 items-center justify-center p-10">
          <form onSubmit={submit} className="flex w-full flex-col items-center gap-3">
            <h2 className="mb-2 text-xl font-semibold tracking-tight">
              {mode === 'login' ? '登录' : '注册'}
            </h2>
            <Field
              label="用户名"
              type="text"
              value={username}
              onChange={setUsername}
              placeholder="请输入用户名"
              minLength={2}
              autoComplete="username"
            />
            <Field
              label="密码"
              type="password"
              value={password}
              onChange={setPassword}
              placeholder={mode === 'register' ? '密码（至少 6 位）' : '请输入密码'}
              minLength={6}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            />
            <Button type="submit" disabled={busy} className="mt-3 w-[160px]">
              {busy ? '请稍候...' : mode === 'login' ? '登录' : '注册'}
            </Button>
          </form>
        </div>
      </div>
    </div>
  );
}
