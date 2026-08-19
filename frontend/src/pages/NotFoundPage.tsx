import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';

export function NotFoundPage() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4">
      <h1 className="text-4xl font-bold text-foreground">404</h1>
      <p className="text-sm text-muted-foreground">页面不存在</p>
      <Button asChild variant="outline">
        <Link to="/chat">返回首页</Link>
      </Button>
    </div>
  );
}
