import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button } from '@/components/ui/Button';

export class ErrorBoundary extends Component<
  { children: ReactNode },
  { error: Error | null }
> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('render error', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="flex h-full flex-col items-center justify-center gap-3 bg-bg p-8 text-center">
          <div className="text-sm font-semibold text-ink">界面遇到了一个问题</div>
          <div className="max-w-lg selectable text-xs leading-relaxed text-ink-3">
            {this.state.error.message}
          </div>
          <Button variant="primary" onClick={() => window.location.reload()}>
            重新加载
          </Button>
        </div>
      );
    }
    return this.props.children;
  }
}
