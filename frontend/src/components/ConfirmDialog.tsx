import {AnimatePresence, motion} from 'framer-motion';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string | React.ReactNode;
  confirmText?: string;
  cancelText?: string;
  confirmVariant?: 'danger' | 'primary' | 'warning';
  onConfirm: () => void;
  onCancel: () => void;
  loading?: boolean;
  customContent?: React.ReactNode;
  hideButtons?: boolean;
}

export default function ConfirmDialog({
  open,
  title,
  message,
  confirmText = '确定',
  cancelText = '取消',
  confirmVariant = 'primary',
  onConfirm,
  onCancel,
  loading = false,
  customContent,
  hideButtons = false
}: ConfirmDialogProps) {
  if (!open) return null;

  const variantStyles = {
    danger: 'bg-red-600 hover:bg-red-700 shadow-red-600/20',
    primary: 'bg-primary-600 hover:bg-primary-700 shadow-primary-600/20',
    warning: 'bg-amber-500 hover:bg-amber-600 shadow-amber-500/20'
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          {/* 背景遮罩 */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onCancel}
            className="fixed inset-0 bg-slate-950/45 backdrop-blur-sm z-50"
          />

          {/* 对话框 */}
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={(e) => e.stopPropagation()}
              className="w-full max-w-md rounded-[28px] border border-slate-200/80 bg-white p-7 shadow-2xl dark:border-slate-800 dark:bg-slate-900"
            >
              {/* 标题 */}
                <h3 className="mb-3 text-xl font-bold tracking-tight text-slate-950 dark:text-white">
                {title}
              </h3>

              {/* 内容 */}
                <div className="mb-7 text-sm leading-6 text-slate-600 dark:text-slate-300">
                {typeof message === 'string' ? (
                  message && <p className="whitespace-pre-line">{message}</p>
                ) : (
                  message
                )}
                {customContent}
              </div>

              {/* 按钮 */}
              {!hideButtons && (
                <div className="flex gap-3 justify-end">
                  <motion.button
                    onClick={onCancel}
                    disabled={loading}
                    className="rounded-xl border border-slate-200 px-5 py-2.5 font-medium text-slate-700 transition-all hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    {cancelText}
                  </motion.button>
                  <motion.button
                    onClick={onConfirm}
                    disabled={loading}
                    className={`rounded-xl px-5 py-2.5 font-semibold text-white shadow-md transition-all disabled:cursor-not-allowed disabled:opacity-50 ${variantStyles[confirmVariant]}`}
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    {loading ? (
                      <span className="flex items-center gap-2">
                        <motion.span
                          className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full"
                          animate={{ rotate: 360 }}
                          transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                        />
                        处理中...
                      </span>
                    ) : (
                      confirmText
                    )}
                  </motion.button>
                </div>
              )}
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  );
}
