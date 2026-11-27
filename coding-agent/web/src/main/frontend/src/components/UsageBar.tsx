export function quotaWindowLabel(seconds: number | null, label: string): string {
  if (seconds == null || seconds <= 0) {
    return label || 'Quota';
  }
  if (seconds === 604800) {
    return 'Weekly';
  }
  if (seconds % 86400 === 0) {
    return `${seconds / 86400}d`;
  }
  if (seconds % 3600 === 0) {
    return `${seconds / 3600}h`;
  }
  return seconds % 60 === 0 ? `${seconds / 60}m` : `${seconds}s`;
}

export function ContextBar({ percent, remaining = false }: { percent: number; remaining?: boolean }) {
  const clamped = Math.min(100, Math.max(0, percent));
  const color = remaining
    ? clamped <= 10 ? 'var(--yca-error)' : clamped <= 25 ? 'var(--yca-warning)' : 'var(--yca-success)'
    : clamped >= 90 ? 'var(--yca-error)' : clamped >= 70 ? 'var(--yca-warning)' : 'var(--yca-success)';
  return (
    <div className="mt-1 h-1 w-full overflow-hidden rounded-full" style={{ background: 'var(--yca-panel-2)' }}>
      <div className="h-full rounded-full transition-all" style={{ width: `${clamped}%`, background: color }} />
    </div>
  );
}

export function formatQuotaReset(resetAtEpochSeconds: number, nowEpochSeconds = Math.floor(Date.now() / 1000)): string {
  const seconds = Math.max(0, resetAtEpochSeconds - nowEpochSeconds);
  if (seconds === 0) {
    return 'now';
  }
  if (seconds >= 86400) {
    return `in ${Math.floor(seconds / 86400)}d ${Math.floor((seconds % 86400) / 3600)}h`;
  }
  if (seconds >= 3600) {
    return `in ${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
  }
  return `in ${Math.max(1, Math.ceil(seconds / 60))}m`;
}
