import { useEffect } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';
import { AppLayout } from '@/components/layout/AppLayout';
import { LoginPage } from '@/pages/LoginPage';
import { ChatPage } from '@/pages/ChatPage';
import { LessonPage } from '@/pages/LessonPage';
import { AdminPromptsPage } from '@/pages/AdminPromptsPage';
import { NotFoundPage } from '@/pages/NotFoundPage';

/** 路由守卫: 未登录跳转 /login */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  const location = useLocation();
  if (!token) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return <>{children}</>;
}

export function App() {
  const { initialized, init, token } = useAuthStore();

  useEffect(() => {
    init();
  }, [init]);

  if (!initialized) {
    return <div className="flex h-full items-center justify-center text-sm text-muted-foreground">加载中...</div>;
  }

  return (
    <Routes>
      <Route path="/login" element={token ? <Navigate to="/chat" replace /> : <LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route path="/chat" element={<ChatPage />} />
        <Route path="/lesson" element={<LessonPage />} />
        <Route path="/admin/prompts" element={<AdminPromptsPage />} />
        <Route path="/" element={<Navigate to="/chat" replace />} />
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
