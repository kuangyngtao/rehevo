import type { ReactNode } from 'react';
import { motion } from 'framer-motion';

interface InterviewPageHeaderProps {
  title: string;
  subtitle: string;
  icon: ReactNode;
}

export default function InterviewPageHeader({
  title,
  subtitle,
  icon,
}: InterviewPageHeaderProps) {
  return (
    <motion.div
      className="text-center mb-10"
      initial={{ opacity: 0, y: -20 }}
      animate={{ opacity: 1, y: 0 }}
    >
      <h1 className="text-4xl font-bold text-slate-950 dark:text-white mb-3 flex items-center justify-center gap-3">
        <div className="w-12 h-12 bg-primary-600 rounded-2xl flex items-center justify-center shadow-md shadow-primary-500/20">
          {icon}
        </div>
        {title}
      </h1>
      <p className="text-slate-500 dark:text-slate-400">{subtitle}</p>
    </motion.div>
  );
}
