// Adapted from assistant-ui Elements (MIT). See LICENSE.assistant-ui and
// documentation/src/main/minisite/content/features/ui-advanced.adoc for provenance.
// Only recipes used by the selected Elements are retained. No global theme changes.
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...values: ClassValue[]) {
  return twMerge(clsx(values));
}

export const paper = 'bg-[var(--yca-panel)] text-[var(--yca-text)] border border-[var(--yca-border)]';
export const field = 'bg-[var(--yca-panel-2)] text-[var(--yca-text)] forced-colors:border';
export const mono = 'font-mono text-[11px] tracking-tight';
export const ghostButton = 'flex items-center justify-center rounded-full text-[var(--yca-muted)] hover:bg-[var(--yca-panel-2)] focus-visible:outline focus-visible:outline-[var(--yca-ring)] disabled:opacity-50';
export const inkButton = 'bg-[var(--yca-primary)] text-[var(--yca-bg)] hover:opacity-90 focus-visible:outline focus-visible:outline-[var(--yca-ring)] forced-colors:border';
