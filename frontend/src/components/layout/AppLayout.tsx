import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { BookOpenText, LogOut, Menu, MessageSquareText, Settings2, X } from 'lucide-react';
import { useAuthStore } from '@/store/auth';
import { SessionList } from '@/components/layout/SessionList';
import { SystemStatus } from '@/components/layout/SystemStatus';
import { cn } from '@/lib/utils';

const NAV_ITEMS = [
  { to: '/chat', label: '智能对话', icon: MessageSquareText },
  { to: '/lesson', label: '教案生成', icon: BookOpenText },
  { to: '/admin/prompts', label: '提示词管理', icon: Settings2 },
];

function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" className={cn('h-5 w-5', className)}>
      <rect width="64" height="64" rx="14" fill="currentColor" opacity="0.12" />
      <path
        d="M32 14 L20 20 V44 L32 38 L44 44 V20 Z"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinejoin="round"
      />
      <line x1="32" y1="14" x2="32" y2="38" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

/**
 * 全局布局 — 浅色侧边栏 + 内容区
 * 参照 Vercel AI Chatbot / shadcn sidebar 风格: 白底、细边框、中性配色
 */
export function AppLayout() {
  const username = useAuthStore((s) => s.username);
  const logout = useAuthStore((s) => s.logout);
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(false);

  const showSessions = location.pathname === '/chat';

  useEffect(() => {
    setCollapsed(window.innerWidth < 768);
  }, [location.pathname]);

  return (
    <div className="flex h-full">
      {/* 浅色侧边栏 */}
      <aside
        className={cn(
          'flex h-full shrink-0 flex-col border-r bg-sidebar text-sidebar-foreground transition-all duration-200',
          collapsed ? 'w-0 overflow-hidden md:w-14' : 'w-60',
        )}
      >
        <div className="flex h-14 items-center gap-2.5 px-4">
          <BrandMark className="text-foreground" />
          {!collapsed && <span className="text-[15px] font-semibold tracking-tight">GagneFlow</span>}
        </div>

        <nav className="flex flex-col gap-0.5 px-2 pb-2">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
                  isActive && 'bg-sidebar-accent text-sidebar-accent-foreground',
                  collapsed && 'justify-center px-0',
                )
              }
              title={item.label}
            >
              <item.icon className="h-4 w-4 shrink-0" />
              {!collapsed && <span>{item.label}</span>}
            </NavLink>
          ))}
        </nav>

        {showSessions && !collapsed && <SessionList />}

        <div className="mt-auto flex items-center gap-2.5 border-t px-4 py-3">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
            {(username || 'U').charAt(0).toUpperCase()}
          </div>
          <div className="min-w-0 flex-1">
            <div className="truncate text-xs font-semibold">{username || '未登录'}</div>
            <button
              type="button"
              className="text-[10px] text-muted-foreground hover:text-foreground"
              onClick={() => void logout()}
            >
              退出登录
            </button>
          </div>
          {!collapsed && <SystemStatus />}
        </div>
      </aside>

      {/* 折叠开关 (移动端) */}
      <button
        type="button"
        aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
        onClick={() => setCollapsed((v) => !v)}
        className="absolute left-2 top-2 z-40 flex h-8 w-8 items-center justify-center rounded-lg border bg-background text-muted-foreground shadow-sm md:hidden"
      >
        {collapsed ? <Menu className="h-4 w-4" /> : <X className="h-4 w-4" />}
      </button>

      {/* 内容区 */}
      <main className="relative min-w-0 flex-1 overflow-hidden bg-background">
        <Outlet />
      </main>
    </div>
  );
}
