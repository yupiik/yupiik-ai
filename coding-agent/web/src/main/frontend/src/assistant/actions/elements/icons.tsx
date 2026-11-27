// Small decorative SVG adapters; avoid requiring lucide-react for three Elements.
import type { ComponentProps } from 'react';

function Icon({ children, ...props }: ComponentProps<'svg'>) {
  return <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{children}</svg>;
}

export function CheckIcon(props: ComponentProps<'svg'>) {
  return <Icon {...props}><path d="m5 12 4 4L19 6" /></Icon>;
}
export function XIcon(props: ComponentProps<'svg'>) {
  return <Icon {...props}><path d="m6 6 12 12M18 6 6 18" /></Icon>;
}
export function Loader2Icon(props: ComponentProps<'svg'>) {
  return <Icon {...props}><path d="M20 12a8 8 0 1 1-8-8" /></Icon>;
}
export function TerminalIcon(props: ComponentProps<'svg'>) {
  return <Icon {...props}><path d="m4 5 6 6-6 6m9 0h7" /></Icon>;
}
export function PlugIcon(props: ComponentProps<'svg'>) {
  return <Icon {...props}><path d="M9 3v5m6-5v5M6 8h12v3a6 6 0 0 1-12 0V8Zm6 9v4" /></Icon>;
}
