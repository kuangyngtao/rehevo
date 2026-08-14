// frontend/src/components/interviewschedule/InterviewEvent.tsx

import React from 'react';
import { motion } from 'framer-motion';
import type { InterviewSchedule } from '../../types/interviewSchedule';

interface InterviewEventProps {
  event: InterviewSchedule;
}

export const InterviewEvent: React.FC<InterviewEventProps> = ({ event }) => {
  const statusConfig = {
    PENDING: {
      bg: 'bg-blue-100 dark:bg-blue-950/55',
      text: 'text-blue-900 dark:text-blue-100',
      border: 'border-blue-200 dark:border-blue-900/70',
    },
    COMPLETED: {
      bg: 'bg-emerald-100 dark:bg-emerald-950/55',
      text: 'text-emerald-900 dark:text-emerald-100',
      border: 'border-emerald-200 dark:border-emerald-900/70',
    },
    CANCELLED: {
      bg: 'bg-slate-100 dark:bg-slate-800',
      text: 'text-slate-700 dark:text-slate-200',
      border: 'border-slate-200 dark:border-slate-700',
    },
    RESCHEDULED: {
      bg: 'bg-amber-100 dark:bg-amber-950/55',
      text: 'text-amber-900 dark:text-amber-100',
      border: 'border-amber-200 dark:border-amber-900/70',
    },
  };

  const config = statusConfig[event.status];

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      whileHover={{ scale: 1.02 }}
      className={`h-full overflow-hidden rounded-lg border p-1.5 shadow-sm transition-shadow hover:shadow-md ${config.bg} ${config.text} ${config.border}`}
    >
      <div className="font-display font-semibold text-xs leading-tight mb-0.5 break-words">{event.companyName}</div>
      <div className="text-xs opacity-90 font-medium leading-tight break-words">{event.position}</div>
      {event.roundNumber > 1 && (
        <div className="text-xs opacity-75 mt-0.5 font-medium leading-tight">第{event.roundNumber}轮</div>
      )}
    </motion.div>
  );
};
