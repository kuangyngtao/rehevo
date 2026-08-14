// frontend/src/components/interviewschedule/CalendarErrorBoundary.tsx

import { Component, type ErrorInfo, type ReactNode } from 'react';
import { CalendarX, RefreshCw } from 'lucide-react';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class CalendarErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null,
  };

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('Calendar Error:', error, errorInfo);
  }

  public render() {
    if (this.state.hasError) {
      return (
        <div className="rounded-[28px] border border-red-100 bg-white p-6 shadow-sm dark:border-red-950/70 dark:bg-slate-900">
          <div className="text-center py-12">
            <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-2xl bg-red-50 text-red-600 dark:bg-red-950/40 dark:text-red-300">
              <CalendarX className="h-7 w-7" />
            </div>
            <h3 className="mb-2 text-xl font-semibold tracking-tight text-slate-900 dark:text-white">
              日历渲染出错
            </h3>
            <p className="mb-5 text-sm text-slate-600 dark:text-slate-400">
              {this.state.error?.message || '未知错误'}
            </p>
            <button
              onClick={() => {
                this.setState({ hasError: false, error: null });
                window.location.reload();
              }}
              className="inline-flex items-center gap-2 rounded-xl bg-primary-600 px-4 py-2.5 text-sm font-medium text-white shadow-md shadow-primary-600/20 transition-colors hover:bg-primary-700"
            >
              <RefreshCw className="h-4 w-4" />
              刷新页面
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
