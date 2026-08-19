import { Toaster as Sonner } from 'sonner';
import { cn } from '@/lib/utils';

type ToasterProps = React.ComponentProps<typeof Sonner>;

/** 统一 toast 层 (E4: z 层级最高) */
function Toaster({ ...props }: ToasterProps) {
  return (
    <Sonner
      className="toaster group"
      position="top-center"
      toastOptions={{
        classNames: {
          toast: cn(
            'group toast group-[.toaster]:bg-background group-[.toaster]:text-foreground group-[.toaster]:border-border group-[.toaster]:shadow-lg',
          ),
          description: 'group-[.toast]:text-muted-foreground',
          success: 'group-[.toaster]:border-emerald-200 group-[.toaster]:bg-emerald-50 group-[.toaster]:text-emerald-900',
          error: 'group-[.toaster]:border-red-200 group-[.toaster]:bg-red-50 group-[.toaster]:text-red-900',
          warning: 'group-[.toaster]:border-amber-200 group-[.toaster]:bg-amber-50 group-[.toaster]:text-amber-900',
        },
      }}
      {...props}
    />
  );
}

export { Toaster };
