// Local YCA theme adapter for assistant-ui Elements (MIT); see ../LICENSE.assistant-ui and
// documentation/src/main/minisite/content/features/ui-advanced.adoc for provenance.
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...values: ClassValue[]) {
  return twMerge(clsx(values));
}

export const paper = 'border border-[var(--yca-border)] bg-[var(--yca-panel-2)] text-[var(--yca-text)]';
export const field = 'border border-[var(--yca-border)] bg-[var(--yca-panel)]';
export const mono = 'font-mono text-[11px] tracking-tight';
export const ghostButton = 'flex items-center justify-center rounded-full border border-[var(--yca-border)] text-[var(--yca-text)] hover:bg-[var(--yca-accent-soft)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2';
export const inkButton = 'bg-[var(--yca-primary)] text-[var(--yca-panel)] rounded-full hover:opacity-90 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 disabled:opacity-40';
